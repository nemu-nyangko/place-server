package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.domain.canvas.config.Palette
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
class CanvasServiceTest {

    @Autowired lateinit var canvasService: CanvasService
    @Autowired lateinit var pixelStockService: PixelStockService
    @Autowired lateinit var mirror: CanvasMirror
    @Autowired lateinit var stringRedisTemplate: ReactiveStringRedisTemplate

    private fun guest() = PlaceUser("g:${UUID.randomUUID()}", UserType.GUEST)
    private fun member() = PlaceUser("m:${System.nanoTime()}", UserType.MEMBER, "테스트")

    /** Pub/Sub 을 거쳐 미러에 반영될 때까지 대기 */
    private suspend fun awaitMirror(x: Int, y: Int, color: Int) = withContext(Dispatchers.Default) {
        repeat(150) {
            if (mirror.ready && (mirror.region(x, y, 1, 1)[0].toInt() and 0xFF) == color) return@withContext
            delay(20)
        }
        fail<Unit>("mirror not updated at ($x,$y)")
    }

    @Test
    fun `픽셀을 찍으면 Redis 원본과 메모리 미러 모두 반영`() = runTest {
        val stock = canvasService.placePixel(member(), 10, 20, 5)
        assertTrue(stock.consumed)
        assertEquals(29, stock.stock)
        val canvas = canvasService.loadCanvasFromRedis()
        assertEquals(5, canvas[20 * 512 + 10].toInt())
        awaitMirror(10, 20, 5)
    }

    @Test
    fun `게스트는 5개, 회원은 30개 스톡에서 시작`() = runTest {
        assertEquals(5, pixelStockService.getStock(guest()).maxStock)
        assertEquals(30, pixelStockService.getStock(member()).stock)
    }

    @Test
    fun `게스트 스톡을 다 쓰면 더 이상 찍히지 않음`() = runTest {
        val user = guest()
        repeat(5) { assertTrue(canvasService.placePixel(user, 100 + it, 100, 3).consumed) }
        val sixth = canvasService.placePixel(user, 200, 201, 4)
        assertFalse(sixth.consumed)
        assertEquals(0, sixth.stock)
        assertTrue(sixth.secondsUntilNextCharge in 1..60)
        assertNotEquals(4, canvasService.loadCanvasFromRedis()[201 * 512 + 200].toInt())
    }

    @Test
    fun `시간이 지나면 충전되고 최대치를 넘지 않음`() = runTest {
        val user = guest()
        repeat(5) { canvasService.placePixel(user, it, 0, 1) }
        val hash = stringRedisTemplate.opsForHash<String, String>()
        val past = Instant.now().epochSecond - 125  // 60초 주기 → 2개 충전
        hash.put(pixelStockService.stockKey(user.id), "lastRefillAt", past.toString()).awaitSingle()
        assertEquals(2, pixelStockService.getStock(user).stock)

        hash.put(pixelStockService.stockKey(user.id), "lastRefillAt", "0").awaitSingle()
        assertEquals(5, pixelStockService.getStock(user).stock)
        // 충전된 상태에서 하나 찍으면 4개
        assertEquals(4, canvasService.placePixel(user, 9, 9, 2).stock)
    }

    @Test
    fun `영역 업데이트와 픽셀 작성자 기록`() = runTest {
        val user = member()
        // 128 이상 확장색이 섞여도 바이트가 깨지지 않아야 한다
        val edits = (0 until 64).map { PixelEdit(300 + it % 8, 300 + it / 8, if (it % 2 == 0) it % 16 else 192 + it) }
        assertEquals(64, canvasService.applyEdits(user, edits))
        val canvas = canvasService.loadCanvasFromRedis()
        // row 1, col 6 → index 14 / row 1, col 7 → 192 + 15
        assertEquals(14, canvas[301 * 512 + 306].toInt())
        assertEquals(207, canvas[301 * 512 + 307].toInt() and 0xFF)
        awaitMirror(307, 301, 207)
        val info = canvasService.getPixelInfo(307, 301)
        assertEquals("테스트", info?.name)
        assertEquals(true, info?.member)
    }

    @Test
    fun `컬러피커 확장색(128 이상)도 그대로 저장되고 미러에 반영`() = runTest {
        assertTrue(canvasService.placePixel(member(), 11, 21, 231).consumed)
        assertEquals(231, canvasService.loadCanvasFromRedis()[21 * 512 + 11].toInt() and 0xFF)
        awaitMirror(11, 21, 231)
    }

    @Test
    fun `팔레트는 256색, 기본 16색은 그대로`() {
        assertEquals(256, Palette.COLORS.size)
        assertEquals("#009EAA", Palette.COLORS[9].hex)
        assertEquals(Palette.COLORS.indices.toList(), Palette.COLORS.map { it.index })
        assertEquals(256, Palette.COLORS.map { it.hex }.size)
    }
}
