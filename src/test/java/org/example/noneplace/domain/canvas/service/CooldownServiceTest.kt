package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun `AI 리터치 쿨타임은 동시 요청 중 하나만 선점`() = runTest {
        val userId = "m:${UUID.randomUUID()}"
        val results = (1..10).map { async { cooldownService.claimAiRetouch(userId) } }.awaitAll()
        assertEquals(1, results.count { it == null })
        assertTrue(cooldownService.getRemainingAiRetouchCooldown(userId)!! > 0)
    }

    @Test
    fun `선점 해제 후 다시 사용 가능`() = runTest {
        val userId = "m:${UUID.randomUUID()}"
        assertNull(cooldownService.claimAiRetouch(userId))
        cooldownService.releaseAiRetouch(userId)
        assertNull(cooldownService.getRemainingAiRetouchCooldown(userId))
        assertNull(cooldownService.claimAiRetouch(userId))
    }
}
