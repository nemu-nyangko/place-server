package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.canvas.config.CanvasProperties
import org.example.noneplace.domain.user.model.PlaceUser
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 픽셀 스톡(토큰 버킷) 조회 서비스.
 *
 * 등급별로 최대 스톡/충전 주기가 다르다 — [CanvasProperties.guest], [CanvasProperties.member].
 * 스톡은 Redis Hash `pixel-stock:<userId>` 에 {stock, lastRefillAt} 로 저장하고 읽을 때 지연 계산한다.
 * 실제 차감은 픽셀 기록과 함께 [CanvasService.placePixel] 의 Lua 스크립트에서 원자적으로 일어난다.
 */
@Service
class PixelStockService(
    private val stringRedisTemplate: ReactiveStringRedisTemplate,
    private val properties: CanvasProperties,
) {
    fun tierOf(user: PlaceUser): CanvasProperties.Tier =
        if (user.isMember) properties.member else properties.guest

    /** 스톡이 가득 차는 시간만큼 TTL 을 두어 그만둔 유저 키를 자동 정리한다. */
    /** 한 번에 찍을 수 있는 최대 칸 수 (편집 모드). 관리자는 [ADMIN_BATCH] */
    fun maxBatch(user: PlaceUser): Int = if (user.isAdmin) ADMIN_BATCH else tierOf(user).maxStock

    fun ttlSeconds(tier: CanvasProperties.Tier): Long = tier.maxStock * tier.regenSeconds

    fun stockKey(userId: String) = "pixel-stock:$userId"

    /** 현재 스톡 상태 조회(차감 없음, Redis 쓰기 없음). */
    suspend fun getStock(user: PlaceUser): PixelStock {
        if (user.isAdmin) return PixelStock.unlimited()
        val tier = tierOf(user)
        val now = Instant.now().epochSecond
        val hash = stringRedisTemplate.opsForHash<String, String>()
            .entries(stockKey(user.id))
            .collectMap({ it.key }, { it.value })
            .awaitSingleOrNull()

        val storedStock = hash?.get("stock")?.toIntOrNull()
        val lastRefillAt = hash?.get("lastRefillAt")?.toLongOrNull()
        if (storedStock == null || lastRefillAt == null) {
            return PixelStock(tier.maxStock, tier.maxStock, tier.regenSeconds, 0, consumed = false)
        }

        val elapsed = (now - lastRefillAt).coerceAtLeast(0)
        val gained = elapsed / tier.regenSeconds
        val stock = (storedStock + gained).coerceAtMost(tier.maxStock.toLong()).toInt()
        val secondsUntilNext = if (stock >= tier.maxStock) {
            0L
        } else {
            val effectiveLast = lastRefillAt + gained * tier.regenSeconds
            tier.regenSeconds - ((now - effectiveLast) % tier.regenSeconds)
        }
        return PixelStock(stock, tier.maxStock, tier.regenSeconds, secondsUntilNext, consumed = false)
    }

    companion object {
        const val ADMIN_BATCH = 1000
    }
}

/** 픽셀 스톡 상태. [consumed] 는 픽셀 찍기 시 실제 차감 여부. */
data class PixelStock(
    val stock: Int,
    val maxStock: Int,
    val regenSeconds: Long,
    val secondsUntilNextCharge: Long,
    val consumed: Boolean,
    /** 관리자: 스톡을 쓰지 않는다. stock/maxStock 은 한 번에 찍을 수 있는 최대 칸 수 */
    val unlimited: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "stock" to stock,
        "maxStock" to maxStock,
        "regenSeconds" to regenSeconds,
        "secondsUntilNextCharge" to secondsUntilNextCharge,
        "unlimited" to unlimited,
    )

    companion object {
        fun unlimited(consumed: Boolean = false) = PixelStock(
            PixelStockService.ADMIN_BATCH, PixelStockService.ADMIN_BATCH, 1, 0, consumed, unlimited = true,
        )
    }
}
