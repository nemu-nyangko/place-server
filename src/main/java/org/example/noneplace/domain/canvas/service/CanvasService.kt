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
import reactor.core.publisher.Flux
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

    suspend fun getRegion(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val canvas = redisTemplate.opsForValue().get(CANVAS_KEY).awaitSingleOrNull()
            ?: return ByteArray(width * height)
        val result = ByteArray(width * height)
        for (row in 0 until height) {
            val srcOffset = (y + row) * WIDTH + x
            canvas.copyInto(result, row * width, srcOffset, srcOffset + width)
        }
        return result
    }

    suspend fun updateRegion(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray, userId: String) {
        redisTemplate.execute { conn ->
            Flux.range(0, height).flatMap { row ->
                val offset = ((y + row).toLong() * WIDTH) + x
                val rowData = pixels.copyOfRange(row * width, (row + 1) * width)
                conn.stringCommands().setRange(
                    ByteBuffer.wrap(CANVAS_KEY.toByteArray()),
                    ByteBuffer.wrap(rowData),
                    offset
                )
            }
        }.collectList().awaitSingle()

        val pixelsList = pixels.joinToString(",") { (it.toInt() and 0xFF).toString() }
        val message = """{"type":"REGION_UPDATE","x":$x,"y":$y,"width":$width,"height":$height,"pixels":[$pixelsList],"userId":"$userId"}"""
        stringRedisTemplate.convertAndSend(CANVAS_CHANNEL, message).awaitSingleOrNull()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun initCanvas() {
        val emptyCanvas = ByteArray(WIDTH * WIDTH)
        redisTemplate.opsForValue().setIfAbsent(CANVAS_KEY, emptyCanvas).subscribe()
    }
}
