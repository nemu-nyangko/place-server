package org.example.noneplace.global.ratelimit

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Instant

/** 고정 윈도우 카운터 기반의 단순 레이트 리밋 (INCR + EXPIRE 원자 실행). */
@Service
class RateLimitService(
    private val stringRedisTemplate: ReactiveStringRedisTemplate,
) {
    private val script: RedisScript<Long> = RedisScript.of(LUA, Long::class.javaObjectType)

    /** 허용되면 true. [windowSeconds] 윈도우 안에서 [limit] 회까지 허용한다. */
    suspend fun tryAcquire(bucket: String, limit: Long, windowSeconds: Long): Boolean {
        val window = Instant.now().epochSecond / windowSeconds
        val count = stringRedisTemplate.execute(
            script,
            listOf("rate:$bucket:$window"),
            listOf(windowSeconds.toString()),
        ).awaitSingle()
        return count <= limit
    }

    companion object {
        private val LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
            return c
        """.trimIndent()
    }
}
