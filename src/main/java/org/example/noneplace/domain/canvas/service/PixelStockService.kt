package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 픽셀 그리기 스톡(충전) 관리 서비스.
 *
 * 유저는 최대 [maxStock]개의 스톡을 보유하며 [regenSeconds]초마다 1개씩 충전된다.
 * 스톡은 Redis Hash `pixel-stock:<userId>` 에 {stock, lastRefillAt} 로 저장하고,
 * 실제 값은 읽을 때 경과 시간으로 지연 계산(토큰 버킷)한다.
 * 충전 계산 + 차감 + 저장은 Lua 스크립트로 원자 실행하여 연타/동시 요청에도 이중 차감이 없다.
 */
@Service
class PixelStockService(
    private val stringRedisTemplate: ReactiveStringRedisTemplate
) {

    @Value("\${canvas.pixel-regen-seconds:30}")
    private var regenSeconds: Long = 30

    @Value("\${canvas.pixel-max-stock:20}")
    private var maxStock: Int = 20

    /** 스톡을 가득 채우는 데 걸리는 시간 이상으로 TTL을 잡아, 그만둔 유저의 키를 자동 정리한다. */
    private val ttlSeconds: Long
        get() = maxStock * regenSeconds

    private fun stockKey(userId: String) = "pixel-stock:$userId"

    @Suppress("UNCHECKED_CAST")
    private val consumeScript: RedisScript<List<Long>> =
        RedisScript.of(CONSUME_LUA, List::class.java) as RedisScript<List<Long>>

    private val refundScript: RedisScript<Long> =
        RedisScript.of(REFUND_LUA, Long::class.javaObjectType)

    /**
     * 스톡 1개를 원자적으로 차감한다.
     * 스톡이 없으면 [PixelStock.consumed] 가 false 이고 차감/저장은 일어나지 않는다.
     */
    suspend fun consume(userId: String): PixelStock {
        val now = Instant.now().epochSecond
        val result = stringRedisTemplate.execute(
            consumeScript,
            listOf(stockKey(userId)),
            listOf(
                now.toString(),
                regenSeconds.toString(),
                maxStock.toString(),
                ttlSeconds.toString()
            )
        ).awaitSingle()

        val stock = (result[0] as Long).toInt()
        val secondsUntilNext = result[1] as Long
        val consumed = (result[2] as Long) == 1L
        return PixelStock(stock, maxStock, secondsUntilNext, consumed)
    }

    /**
     * 차감했던 스톡 1개를 되돌린다(캔버스 쓰기 실패 시 best-effort 환불).
     * 키가 없으면(이미 가득 찬 것으로 간주) 아무 작업도 하지 않는다.
     */
    suspend fun refund(userId: String) {
        stringRedisTemplate.execute(
            refundScript,
            listOf(stockKey(userId)),
            listOf(maxStock.toString(), ttlSeconds.toString())
        ).awaitSingle()
    }

    /**
     * 현재 스톡 상태를 조회한다(차감 없음).
     * 지연 계산만 하고 Redis 에는 쓰지 않는다 — 저장된 {stock, lastRefillAt} 로 언제든 현재값을 재계산할 수 있기 때문.
     */
    suspend fun getStock(userId: String): PixelStock {
        val now = Instant.now().epochSecond
        val hash = stringRedisTemplate.opsForHash<String, String>()
            .entries(stockKey(userId))
            .collectMap({ it.key }, { it.value })
            .awaitSingleOrNull()

        val storedStock = hash?.get("stock")?.toIntOrNull()
        val lastRefillAt = hash?.get("lastRefillAt")?.toLongOrNull()

        // 키가 없는 신규 유저는 가득 찬 상태로 간주
        if (storedStock == null || lastRefillAt == null) {
            return PixelStock(maxStock, maxStock, 0, consumed = false)
        }

        val elapsed = (now - lastRefillAt).coerceAtLeast(0)
        val gained = elapsed / regenSeconds
        val stock = (storedStock + gained).coerceAtMost(maxStock.toLong()).toInt()

        val secondsUntilNext = if (stock >= maxStock) {
            0L
        } else {
            val effectiveLast = lastRefillAt + gained * regenSeconds
            regenSeconds - ((now - effectiveLast) % regenSeconds)
        }
        return PixelStock(stock, maxStock, secondsUntilNext, consumed = false)
    }

    companion object {
        /**
         * KEYS[1] = stock key
         * ARGV = [now(epochSec), regenSeconds, maxStock, ttlSeconds]
         * 반환: { stock, secondsUntilNextCharge, consumed(1|0) }
         */
        private val CONSUME_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local regen = tonumber(ARGV[2])
            local maxStock = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'stock', 'lastRefillAt')
            local stock = tonumber(data[1])
            local lastRefillAt = tonumber(data[2])

            if stock == nil or lastRefillAt == nil then
                stock = maxStock
                lastRefillAt = now
            end

            local elapsed = now - lastRefillAt
            if elapsed < 0 then elapsed = 0 end
            local gained = math.floor(elapsed / regen)
            if gained > 0 then
                stock = stock + gained
                if stock >= maxStock then
                    stock = maxStock
                    lastRefillAt = now
                else
                    lastRefillAt = lastRefillAt + gained * regen
                end
            end

            if stock < 1 then
                local secondsUntilNext = regen - ((now - lastRefillAt) % regen)
                return { 0, secondsUntilNext, 0 }
            end

            stock = stock - 1
            redis.call('HMSET', key, 'stock', stock, 'lastRefillAt', lastRefillAt)
            redis.call('EXPIRE', key, ttl)

            local secondsUntilNext
            if stock >= maxStock then
                secondsUntilNext = 0
            else
                secondsUntilNext = regen - ((now - lastRefillAt) % regen)
            end
            return { stock, secondsUntilNext, 1 }
        """.trimIndent()

        /**
         * KEYS[1] = stock key, ARGV = [maxStock, ttlSeconds]
         * 저장된 stock 을 1 늘려 되돌린다(최대치 캡). 키가 없으면 그대로 둔다.
         */
        private val REFUND_LUA = """
            local key = KEYS[1]
            local maxStock = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])

            local stock = tonumber(redis.call('HGET', key, 'stock'))
            if stock == nil then
                return -1
            end
            stock = stock + 1
            if stock > maxStock then stock = maxStock end
            redis.call('HSET', key, 'stock', stock)
            redis.call('EXPIRE', key, ttl)
            return stock
        """.trimIndent()
    }
}

/** 픽셀 스톡 상태. [consumed] 는 [PixelStockService.consume] 호출 시 실제 차감 여부. */
data class PixelStock(
    val stock: Int,
    val maxStock: Int,
    val secondsUntilNextCharge: Long,
    val consumed: Boolean
)
