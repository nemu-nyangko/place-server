package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CooldownService(
    private val redisTemplate: ReactiveRedisTemplate<String, ByteArray>
) {

    @Value("\${canvas.cooldown-seconds:300}")
    private var cooldownSeconds: Long = 300

    private fun cooldownKey(userId: String) = "cooldown:$userId"

    suspend fun getRemainingCooldown(userId: String): Long? {
        val ttl = redisTemplate.getExpire(cooldownKey(userId)).awaitSingleOrNull() ?: return null
        return if (ttl.seconds > 0) ttl.seconds else null
    }
    suspend fun setCooldown(userId: String) {
        redisTemplate.opsForValue()
            .set(cooldownKey(userId), byteArrayOf(1), Duration.ofSeconds(cooldownSeconds))
            .awaitSingleOrNull()
    }
}
