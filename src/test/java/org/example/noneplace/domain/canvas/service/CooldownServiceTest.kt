package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.test.runTest
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID

@SpringBootTest
@Import(TestRedisConfig::class)
class CooldownServiceTest {

    @Autowired
    lateinit var cooldownService: CooldownService

    @Test
    fun `AI 리터치 쿨타임 등록 전에는 남은 시간이 null`() = runTest {
        val userId = UUID.randomUUID().toString()

        val remaining = cooldownService.getRemainingAiRetouchCooldown(userId)

        assertNull(remaining)
    }

    @Test
    fun `AI 리터치 쿨타임 등록 후 남은 시간이 존재`() = runTest {
        val userId = UUID.randomUUID().toString()

        cooldownService.setAiRetouchCooldown(userId)
        val remaining = cooldownService.getRemainingAiRetouchCooldown(userId)

        assertNotNull(remaining)
        assertTrue(remaining!! > 0)
    }

    @Test
    fun `서로 다른 유저는 AI 리터치 쿨타임을 공유하지 않음`() = runTest {
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()

        cooldownService.setAiRetouchCooldown(userA)

        assertNotNull(cooldownService.getRemainingAiRetouchCooldown(userA))
        assertNull(cooldownService.getRemainingAiRetouchCooldown(userB))
    }
}
