package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * AI 리터치 쿨타임 관리 서비스.
 * 픽셀 그리기는 쿨타임이 아닌 스톡(충전) 방식으로 전환되어 [PixelStockService] 가 담당한다.
 */
@Service
class CooldownService(
    private val redisTemplate: ReactiveRedisTemplate<String, ByteArray>
) {

    @Value("\${canvas.ai-retouch-cooldown-seconds:300}")
    private var aiRetouchCooldownSeconds: Long = 300

    private fun aiRetouchCooldownKey(userId: String) = "ai-retouch-cooldown:$userId"

    suspend fun getRemainingAiRetouchCooldown(userId: String): Long? {
        val ttl = redisTemplate.getExpire(aiRetouchCooldownKey(userId)).awaitSingleOrNull() ?: return null
        return if (ttl.seconds > 0) ttl.seconds else null
    }

    suspend fun setAiRetouchCooldown(userId: String) {
        redisTemplate.opsForValue()
            .set(aiRetouchCooldownKey(userId), byteArrayOf(1), Duration.ofSeconds(aiRetouchCooldownSeconds))
            .awaitSingleOrNull()
    }
}
