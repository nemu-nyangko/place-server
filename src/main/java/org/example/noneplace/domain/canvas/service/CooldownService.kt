package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.canvas.config.CanvasProperties
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * AI 리터치 쿨타임 관리. 픽셀 그리기는 [PixelStockService] 의 스톡 방식을 쓴다.
 */
@Service
class CooldownService(
    private val redisTemplate: ReactiveRedisTemplate<String, ByteArray>,
    private val properties: CanvasProperties,
) {
    private fun aiRetouchCooldownKey(userId: String) = "ai-retouch-cooldown:$userId"

    suspend fun getRemainingAiRetouchCooldown(userId: String): Long? {
        val ttl = redisTemplate.getExpire(aiRetouchCooldownKey(userId)).awaitSingleOrNull() ?: return null
        return if (ttl.seconds > 0) ttl.seconds else null
    }

    /**
     * 쿨타임을 원자적으로 선점한다(SET NX EX). 동시 요청이 와도 하나만 성공한다.
     * 이미 쿨타임 중이면 남은 초를, 선점에 성공하면 null 을 반환한다.
     */
    suspend fun claimAiRetouch(userId: String): Long? {
        val claimed = redisTemplate.opsForValue()
            .setIfAbsent(
                aiRetouchCooldownKey(userId),
                byteArrayOf(1),
                Duration.ofSeconds(properties.aiRetouchCooldownSeconds),
            )
            .awaitSingleOrNull() ?: false
        if (claimed) return null
        return getRemainingAiRetouchCooldown(userId) ?: 1
    }

    /** 리터치 처리 실패 시 선점한 쿨타임을 되돌린다 */
    suspend fun releaseAiRetouch(userId: String) {
        redisTemplate.delete(aiRetouchCooldownKey(userId)).awaitSingleOrNull()
    }
}
