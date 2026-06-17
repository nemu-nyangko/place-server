package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.test.runTest
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestRedisConfig::class)
class CanvasServiceTest {

    @Autowired
    lateinit var canvasService: CanvasService

    @Test
    fun `픽셀 업데이트 비동기 로직 검증`() = runTest {
        // Given
        val x = 10
        val y = 20
        val color: Byte = 5  // Green (유효한 팔레트 색상)
        val userId = "test-user-uuid-1234"
        val offset = (y * 512 + x).toLong()

        // When
        canvasService.updatePixel(x, y, color, userId)

        // Then
        val canvas = canvasService.getCanvas()
        assertNotNull(canvas)
        assertEquals(color, canvas!![offset.toInt()])
    }

    @Test
    fun `유효하지 않은 색상 인덱스로 픽셀 업데이트 시 예외 발생`() = runTest {
        // Given
        val invalidColor: Byte = 99  // 팔레트에 없는 색상
        val userId = "test-user-uuid-1234"

        // When & Then
        val exception = runCatching {
            canvasService.updatePixel(0, 0, invalidColor, userId)
        }
        assert(exception.isFailure)
        assert(exception.exceptionOrNull() is IllegalArgumentException)
    }
}
