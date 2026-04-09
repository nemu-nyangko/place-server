package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.canvas.present.CanvasWebSocketHandler.Companion.CANVAS_CHANNEL
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.nio.ByteBuffer

@Service
class CanvasService(
    private val redisTemplate: ReactiveRedisTemplate<String, ByteArray>,
    private val stringRedisTemplate: ReactiveStringRedisTemplate
) {
    private val CANVAS_KEY = "canvas"
    private val WIDTH = 512

    data class PixelMessage(val x: Int, val y: Int, val color: Byte, val userId: String)

    suspend fun updatePixel(x: Int, y: Int, colorIdx: Byte, userId: String): Long? {
        require(Palette.isValid(colorIdx)) { "Invalid color index: $colorIdx" }

        val offset = (y.toLong() * WIDTH) + x

        val result = redisTemplate.execute { conn ->
            val keyBuffer = ByteBuffer.wrap(CANVAS_KEY.toByteArray())
            val valueBuffer = ByteBuffer.wrap(byteArrayOf(colorIdx))
            conn.stringCommands().setRange(keyBuffer, valueBuffer, offset)
        }.awaitSingle()

        if (result != null) {
            val message = """{"x":$x,"y":$y,"color":${colorIdx.toInt()},"userId":"$userId"}"""
            stringRedisTemplate.convertAndSend(CANVAS_CHANNEL, message).awaitSingleOrNull()
        }

        return result
    }

    suspend fun getCanvas(): ByteArray? {
        return redisTemplate.opsForValue().get(CANVAS_KEY).awaitSingleOrNull()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun initCanvas() {
        val emptyCanvas = ByteArray(WIDTH * WIDTH)
        redisTemplate.opsForValue().setIfAbsent(CANVAS_KEY, emptyCanvas).subscribe()
    }
}
