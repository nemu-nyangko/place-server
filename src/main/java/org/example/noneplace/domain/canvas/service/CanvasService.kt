package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.user.model.PlaceUser
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 캔버스 읽기/쓰기.
 *
 * 쓰기는 Lua 스크립트 한 번으로 [스톡 차감 → SETRANGE → 작성자 기록 → PUBLISH] 를 원자 실행한다.
 * 요청당 Redis 왕복이 1회라 동시 접속이 몰려도 지연이 일정하고, 기록 순서와 발행 순서가 항상 같다.
 * 읽기는 Pub/Sub 으로 동기화되는 [CanvasMirror] 에서 처리한다.
 */
@Service
class CanvasService(
    private val redisTemplate: ReactiveRedisTemplate<String, ByteArray>,
    private val stringRedisTemplate: ReactiveStringRedisTemplate,
    private val pixelStockService: PixelStockService,
    private val mirror: CanvasMirror,
) {
    @Suppress("UNCHECKED_CAST")
    private val placeScript: RedisScript<List<Long>> =
        RedisScript.of(PLACE_LUA, List::class.java) as RedisScript<List<Long>>

    private val regionScript: RedisScript<Long> = RedisScript.of(REGION_LUA, Long::class.javaObjectType)

    data class PixelInfo(val name: String, val member: Boolean, val placedAt: Long)

    /** 스톡을 1 소모하며 픽셀을 찍는다. 스톡이 없으면 consumed=false 이고 캔버스는 바뀌지 않는다. */
    suspend fun placePixel(user: PlaceUser, x: Int, y: Int, color: Int): PixelStock {
        require(x in 0 until WIDTH && y in 0 until WIDTH) { "Out of bounds" }
        require(Palette.isValid(color)) { "Invalid color index" }
        val tier = pixelStockService.tierOf(user)
        val now = Instant.now().epochSecond
        val result = stringRedisTemplate.execute(
            placeScript,
            listOf(pixelStockService.stockKey(user.id), CANVAS_KEY, OWNER_KEY),
            listOf(
                now.toString(),
                tier.regenSeconds.toString(),
                tier.maxStock.toString(),
                pixelStockService.ttlSeconds(tier).toString(),
                x.toString(),
                y.toString(),
                color.toString(),
                ownerValue(user, now),
                CANVAS_CHANNEL,
            ),
        ).awaitSingle()
        return PixelStock(
            stock = (result[0] as Long).toInt(),
            maxStock = tier.maxStock,
            regenSeconds = tier.regenSeconds,
            secondsUntilNextCharge = result[1] as Long,
            consumed = (result[2] as Long) == 1L,
        )
    }

    /** 영역 덮어쓰기(AI 리터치 결과 반영). [pixels] 는 행 우선 순서, 길이 = width*height */
    suspend fun updateRegion(user: PlaceUser, x: Int, y: Int, width: Int, height: Int, pixels: ByteArray) {
        require(pixels.size == width * height) { "Invalid region data" }
        // 색상 인덱스는 0~255 라 UTF-8 문자열로 넘기면 깨진다 → 16진수 2글자씩 인코딩해 Lua 에서 되돌린다
        val encoded = pixels.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        stringRedisTemplate.execute(
            regionScript,
            listOf(CANVAS_KEY, OWNER_KEY),
            listOf(
                x.toString(), y.toString(), width.toString(), height.toString(),
                encoded, ownerValue(user, Instant.now().epochSecond), CANVAS_CHANNEL,
            ),
        ).awaitSingle()
    }

    fun getRegion(x: Int, y: Int, width: Int, height: Int): ByteArray = mirror.region(x, y, width, height)

    /** Redis 원본 캔버스. 미러 준비 전 폴백 및 초기 적재에 사용 */
    suspend fun loadCanvasFromRedis(): ByteArray {
        redisTemplate.opsForValue().setIfAbsent(CANVAS_KEY, ByteArray(WIDTH * WIDTH)).awaitSingleOrNull()
        return redisTemplate.opsForValue().get(CANVAS_KEY).awaitSingleOrNull() ?: ByteArray(WIDTH * WIDTH)
    }

    suspend fun getPixelInfo(x: Int, y: Int): PixelInfo? {
        require(x in 0 until WIDTH && y in 0 until WIDTH) { "Out of bounds" }
        val raw = stringRedisTemplate.opsForHash<String, String>()
            .get(OWNER_KEY, (y * WIDTH + x).toString())
            .awaitSingleOrNull() ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size < 3) return null
        return PixelInfo(name = parts[2], member = parts[1] == "1", placedAt = parts[0].toLongOrNull() ?: 0)
    }

    private fun ownerValue(user: PlaceUser, now: Long): String =
        "$now|${if (user.isMember) 1 else 0}|${user.displayName.replace('|', ' ')}"

    companion object {
        const val CANVAS_KEY = "canvas"
        const val OWNER_KEY = "canvas-owner"
        const val CANVAS_CHANNEL = "canvas-updates"
        private const val WIDTH = CanvasMirror.WIDTH

        /**
         * KEYS = [stockKey, canvasKey, ownerKey]
         * ARGV = [now, regenSeconds, maxStock, ttl, x, y, color, ownerValue, channel]
         * 반환: { stock, secondsUntilNextCharge, consumed(1|0) }
         * 발행 메시지: struct '>HHB' (x, y, color) 5바이트
         */
        private val PLACE_LUA = """
            local now = tonumber(ARGV[1])
            local regen = tonumber(ARGV[2])
            local maxStock = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local data = redis.call('HMGET', KEYS[1], 'stock', 'lastRefillAt')
            local stock = tonumber(data[1])
            local lastRefillAt = tonumber(data[2])
            if stock == nil or lastRefillAt == nil then
                stock = maxStock
                lastRefillAt = now
            end
            if stock > maxStock then stock = maxStock end

            local elapsed = now - lastRefillAt
            if elapsed < 0 then elapsed = 0 end
            local gained = math.floor(elapsed / regen)
            if gained > 0 then
                stock = stock + gained
                if stock >= maxStock then
                    stock = maxStock
                    lastRefillAt = now
                else
                    lastRefillAt = lastRefillAt + gained * regen
                end
            end
            if stock >= maxStock then lastRefillAt = now end

            if stock < 1 then
                return { 0, regen - ((now - lastRefillAt) % regen), 0 }
            end

            stock = stock - 1
            redis.call('HMSET', KEYS[1], 'stock', stock, 'lastRefillAt', lastRefillAt)
            redis.call('EXPIRE', KEYS[1], ttl)

            local x = tonumber(ARGV[5])
            local y = tonumber(ARGV[6])
            local color = tonumber(ARGV[7])
            local offset = y * 512 + x
            redis.call('SETRANGE', KEYS[2], offset, string.char(color))
            redis.call('HSET', KEYS[3], offset, ARGV[8])
            redis.call('PUBLISH', ARGV[9], struct.pack('>HHB', x, y, color))

            return { stock, regen - ((now - lastRefillAt) % regen), 1 }
        """.trimIndent()

        /**
         * KEYS = [canvasKey, ownerKey]
         * ARGV = [x, y, width, height, pixels(행 우선, 픽셀당 16진수 2글자), ownerValue, channel]
         */
        private val REGION_LUA = """
            local x = tonumber(ARGV[1])
            local y = tonumber(ARGV[2])
            local w = tonumber(ARGV[3])
            local h = tonumber(ARGV[4])
            local px = ARGV[5]
            local parts = {}
            for row = 0, h - 1 do
                local bytes = {}
                for col = 0, w - 1 do
                    local i = (row * w + col) * 2 + 1
                    local c = tonumber(string.sub(px, i, i + 1), 16)
                    bytes[#bytes + 1] = string.char(c)
                    redis.call('HSET', KEYS[2], (y + row) * 512 + x + col, ARGV[6])
                    parts[#parts + 1] = struct.pack('>HHB', x + col, y + row, c)
                end
                redis.call('SETRANGE', KEYS[1], (y + row) * 512 + x, table.concat(bytes))
            end
            redis.call('PUBLISH', ARGV[7], table.concat(parts))
            return #parts
        """.trimIndent()
    }
}
