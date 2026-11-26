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

    private val editsScript: RedisScript<Long> = RedisScript.of(EDITS_LUA, Long::class.javaObjectType)

    data class PixelInfo(val name: String, val member: Boolean, val placedAt: Long)

    /** 스톡을 1 소모하며 픽셀을 찍는다. 스톡이 없으면 consumed=false 이고 캔버스는 바뀌지 않는다. */
    suspend fun placePixel(user: PlaceUser, x: Int, y: Int, color: Int): PixelStock {
        val (stock, placed) = placePixels(user, listOf(x to y), color)
        return stock.copy(consumed = placed == 1)
    }

    /**
     * 여러 칸을 같은 색으로 한 번에 찍는다(편집 모드). 칸마다 스톡 1개.
     * 스톡이 모자라면 앞에서부터 남은 스톡만큼만 찍고, 찍은 칸 수를 돌려준다.
     * 스톡 차감·SETRANGE·작성자 기록·PUBLISH 가 Lua 한 번으로 원자 실행되고, 변경분은 PUBLISH 한 번으로 나간다.
     */
    suspend fun placePixels(user: PlaceUser, cells: List<Pair<Int, Int>>, color: Int): Pair<PixelStock, Int> {
        require(cells.isNotEmpty()) { "No pixels" }
        require(cells.all { (x, y) -> x in 0 until WIDTH && y in 0 until WIDTH }) { "Out of bounds" }
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
                cells.joinToString("") { (x, y) -> "%04x%04x".format(x, y) },
                color.toString(),
                ownerValue(user, now),
                CANVAS_CHANNEL,
                if (user.isAdmin) "1" else "0",
            ),
        ).awaitSingle()
        val placed = (result[2] as Long).toInt()
        if (user.isAdmin) return PixelStock.unlimited(consumed = placed > 0) to placed
        return PixelStock(
            stock = (result[0] as Long).toInt(),
            maxStock = tier.maxStock,
            regenSeconds = tier.regenSeconds,
            secondsUntilNextCharge = result[1] as Long,
            consumed = placed > 0,
        ) to placed
    }

    /**
     * 지정한 칸들만 바꾼다(AI 리터치 결과 반영). 좌표는 캔버스 절대 좌표.
     * 바뀐 칸만 작성자가 갱신되고, 변경분은 한 번의 PUBLISH 로 모두에게 전달된다.
     */
    suspend fun applyEdits(user: PlaceUser, edits: List<PixelEdit>): Int {
        if (edits.isEmpty()) return 0
        require(edits.all { it.x in 0 until WIDTH && it.y in 0 until WIDTH && Palette.isValid(it.color) }) { "Invalid edit" }
        // 칸당 16진수 10글자(x 4, y 4, color 2). 0~255 색은 UTF-8 문자열로 넘기면 깨지므로 16진수로 보낸다
        val encoded = edits.joinToString("") { "%04x%04x%02x".format(it.x, it.y, it.color) }
        return stringRedisTemplate.execute(
            editsScript,
            listOf(CANVAS_KEY, OWNER_KEY),
            listOf(encoded, ownerValue(user, Instant.now().epochSecond), CANVAS_CHANNEL),
        ).awaitSingle().toInt()
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
         * ARGV = [now, regenSeconds, maxStock, ttl, cells(칸당 16진수 8글자: x 4, y 4), color, ownerValue, channel, admin('1'이면 스톡 무시)]
         * 반환: { stock, secondsUntilNextCharge, 찍은 칸 수 } — 스톡이 모자라면 앞에서부터 스톡만큼만 찍는다
         * 발행 메시지: 칸마다 struct '>HHB' (x, y, color) 5바이트를 이어 붙여 한 번에
         */
        private val PLACE_LUA = """
            local now = tonumber(ARGV[1])
            local regen = tonumber(ARGV[2])
            local maxStock = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local cells = ARGV[5]
            local total = math.floor(#cells / 8)
            local stock = 0
            local lastRefillAt = now
            local n = total
            if ARGV[9] ~= '1' then -- 관리자('1')는 스톡을 읽지도 줄이지도 않는다
                local data = redis.call('HMGET', KEYS[1], 'stock', 'lastRefillAt')
                stock = tonumber(data[1])
                lastRefillAt = tonumber(data[2])
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

                if n > stock then n = stock end
                if n < 1 then
                    return { stock, regen - ((now - lastRefillAt) % regen), 0 }
                end

                stock = stock - n
                redis.call('HMSET', KEYS[1], 'stock', stock, 'lastRefillAt', lastRefillAt)
                redis.call('EXPIRE', KEYS[1], ttl)
            end

            local color = tonumber(ARGV[6])
            local ch = string.char(color)
            local parts = {}
            for i = 0, n - 1 do
                local x = tonumber(string.sub(cells, i * 8 + 1, i * 8 + 4), 16)
                local y = tonumber(string.sub(cells, i * 8 + 5, i * 8 + 8), 16)
                local offset = y * 512 + x
                redis.call('SETRANGE', KEYS[2], offset, ch)
                redis.call('HSET', KEYS[3], offset, ARGV[7])
                parts[#parts + 1] = struct.pack('>HHB', x, y, color)
            end
            redis.call('PUBLISH', ARGV[8], table.concat(parts))

            if ARGV[9] == '1' then return { 0, 0, n } end
            return { stock, regen - ((now - lastRefillAt) % regen), n }
        """.trimIndent()

        /**
         * KEYS = [canvasKey, ownerKey]
         * ARGV = [edits(칸당 16진수 10글자: x 4, y 4, color 2), ownerValue, channel]
         */
        private val EDITS_LUA = """
            local px = ARGV[1]
            local parts = {}
            for i = 1, #px, 10 do
                local x = tonumber(string.sub(px, i, i + 3), 16)
                local y = tonumber(string.sub(px, i + 4, i + 7), 16)
                local c = tonumber(string.sub(px, i + 8, i + 9), 16)
                local offset = y * 512 + x
                redis.call('SETRANGE', KEYS[1], offset, string.char(c))
                redis.call('HSET', KEYS[2], offset, ARGV[2])
                parts[#parts + 1] = struct.pack('>HHB', x, y, c)
            end
            redis.call('PUBLISH', ARGV[3], table.concat(parts))
            return #parts
        """.trimIndent()
    }
}
