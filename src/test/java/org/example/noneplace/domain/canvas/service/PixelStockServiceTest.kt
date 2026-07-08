package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.test.runTest
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@Import(TestRedisConfig::class)
class PixelStockServiceTest {

    @Autowired
    lateinit var pixelStockService: PixelStockService

    @Autowired
    lateinit var stringRedisTemplate: ReactiveStringRedisTemplate

    private fun newUser() = UUID.randomUUID().toString()

    /** lastRefillAt 을 과거로 조작해 시간 경과를 시뮬레이션한다. */
    private suspend fun seedStock(userId: String, stock: Int, ageSeconds: Long) {
        val ops = stringRedisTemplate.opsForHash<String, String>()
        val lastRefillAt = Instant.now().epochSecond - ageSeconds
        ops.put("pixel-stock:$userId", "stock", stock.toString()).awaitSingleOrNull()
        ops.put("pixel-stock:$userId", "lastRefillAt", lastRefillAt.toString()).awaitSingleOrNull()
    }

    @Test
    fun `신규 유저는 가득 찬 20개로 시작`() = runTest {
        val stock = pixelStockService.getStock(newUser())

        assertEquals(20, stock.stock)
        assertEquals(20, stock.maxStock)
        assertEquals(0L, stock.secondsUntilNextCharge)
    }

    @Test
    fun `1개 소모하면 19개가 되고 consumed=true`() = runTest {
        val userId = newUser()

        val result = pixelStockService.consume(userId)

        assertTrue(result.consumed)
        assertEquals(19, result.stock)
        assertEquals(19, pixelStockService.getStock(userId).stock)
    }

    @Test
    fun `가득 찬 상태에서 소모하면 다음 충전까지 30초`() = runTest {
        val userId = newUser()

        val result = pixelStockService.consume(userId)

        assertEquals(30L, result.secondsUntilNextCharge)
    }

    @Test
    fun `20개를 모두 소모하면 21번째는 실패하고 stock=0`() = runTest {
        val userId = newUser()

        repeat(20) {
            assertTrue(pixelStockService.consume(userId).consumed, "20회까지는 성공해야 함")
        }

        val failed = pixelStockService.consume(userId)
        assertFalse(failed.consumed)
        assertEquals(0, failed.stock)
        assertTrue(failed.secondsUntilNextCharge in 1..30)
    }

    @Test
    fun `시간이 지나면 스톡이 충전된다`() = runTest {
        val userId = newUser()
        // 스톡 0, 마지막 충전 90초 전 → 3개 충전되어 있어야 함
        seedStock(userId, stock = 0, ageSeconds = 90)

        assertEquals(3, pixelStockService.getStock(userId).stock)
    }

    @Test
    fun `상한을 넘겨 충전되지 않는다`() = runTest {
        val userId = newUser()
        // 스톡 5, 아주 오래 전 → 상한 20으로 캡
        seedStock(userId, stock = 5, ageSeconds = 100_000)

        assertEquals(20, pixelStockService.getStock(userId).stock)
    }

    @Test
    fun `충전 부분 진행률이 소모 후에도 보존된다`() = runTest {
        val userId = newUser()
        // 스톡 0, 25초 경과 → 아직 0개, 다음 충전까지 5초
        seedStock(userId, stock = 0, ageSeconds = 25)
        assertEquals(0, pixelStockService.getStock(userId).stock)
        assertEquals(5L, pixelStockService.getStock(userId).secondsUntilNextCharge)
    }

    @Test
    fun `환불하면 스톡이 1개 늘어난다`() = runTest {
        val userId = newUser()
        pixelStockService.consume(userId)   // 20 -> 19

        pixelStockService.refund(userId)     // 19 -> 20

        assertEquals(20, pixelStockService.getStock(userId).stock)
    }

    @Test
    fun `환불은 상한을 넘지 않는다`() = runTest {
        val userId = newUser()
        seedStock(userId, stock = 20, ageSeconds = 0)

        pixelStockService.refund(userId)

        assertEquals(20, pixelStockService.getStock(userId).stock)
    }
}
