package org.example.noneplace.domain.canvas.service

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * 인스턴스 메모리에 유지하는 캔버스 사본.
 *
 * Redis Pub/Sub 으로 들어오는 변경분을 그대로 적용하므로 Redis 원본과 항상 수렴한다.
 * QR 스캔으로 동시 접속이 몰려도 캔버스 다운로드가 Redis 를 치지 않고,
 * gzip 결과를 짧게 캐시해 같은 압축을 반복하지 않는다.
 */
@Component
class CanvasMirror {
    private val pixels = ByteArray(SIZE)
    private val pending = ArrayList<ByteArray>()
    private var version = 0L

    @Volatile
    private var loaded = false
    val ready: Boolean get() = loaded

    private var gzipCache: ByteArray? = null
    private var gzipVersion = -1L
    private var gzipBuiltAt = 0L

    /** 스냅샷 적재. 적재 전 수신한 변경분을 순서대로 재적용해 경합 중 누락을 막는다. */
    @Synchronized
    fun load(snapshot: ByteArray) {
        snapshot.copyInto(pixels, 0, 0, minOf(snapshot.size, SIZE))
        pending.forEach { applyPacked(it) }
        pending.clear()
        version++
        loaded = true
    }

    /** 5바이트(x:u16, y:u16, color:u8) 단위로 묶인 변경분 적용 */
    @Synchronized
    fun apply(packed: ByteArray) {
        if (!loaded) {
            pending.add(packed)
            return
        }
        applyPacked(packed)
        version++
    }

    private fun applyPacked(packed: ByteArray) {
        var i = 0
        while (i + PIXEL_BYTES <= packed.size) {
            val x = ((packed[i].toInt() and 0xFF) shl 8) or (packed[i + 1].toInt() and 0xFF)
            val y = ((packed[i + 2].toInt() and 0xFF) shl 8) or (packed[i + 3].toInt() and 0xFF)
            if (x in 0 until WIDTH && y in 0 until WIDTH) {
                pixels[y * WIDTH + x] = packed[i + 4]
            }
            i += PIXEL_BYTES
        }
    }

    @Synchronized
    fun snapshot(): ByteArray = pixels.copyOf()

    @Synchronized
    fun region(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val result = ByteArray(width * height)
        for (row in 0 until height) {
            val src = (y + row) * WIDTH + x
            pixels.copyInto(result, row * width, src, src + width)
        }
        return result
    }

    /** 최대 [GZIP_CACHE_MS] 만큼 오래된 gzip 스냅샷. 클라이언트는 WS 변경분으로 차이를 메운다. */
    fun gzipped(): ByteArray {
        val snapshot: ByteArray
        synchronized(this) {
            val cached = gzipCache
            val now = System.currentTimeMillis()
            if (cached != null && (gzipVersion == version || now - gzipBuiltAt < GZIP_CACHE_MS)) {
                return cached
            }
            snapshot = pixels.copyOf()
            gzipVersion = version
            gzipBuiltAt = now
        }
        val out = ByteArrayOutputStream(16 * 1024)
        object : GZIPOutputStream(out) {
            init { def.setLevel(Deflater.BEST_SPEED) }
        }.use { it.write(snapshot) }
        val bytes = out.toByteArray()
        synchronized(this) { gzipCache = bytes }
        return bytes
    }

    companion object {
        const val WIDTH = 512
        const val SIZE = WIDTH * WIDTH
        const val PIXEL_BYTES = 5
        private const val GZIP_CACHE_MS = 250L

        fun pack(x: Int, y: Int, color: Int): ByteArray = byteArrayOf(
            (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte(), color.toByte()
        )
    }
}
