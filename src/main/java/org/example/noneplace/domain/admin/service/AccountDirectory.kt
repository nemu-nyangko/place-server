package org.example.noneplace.domain.admin.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.user.model.PlaceUser
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 로그인한 회원 목록(관리자 화면용).
 * 회원이 /me 를 부르거나(클라이언트가 1분마다 부름) 로그인할 때 마지막 접속 시각과 이름/학번을 남긴다.
 * - `accounts:seen` ZSET: 계정 번호 → 마지막 접속(ms)
 * - `accounts:info` HASH: 계정 번호 → {name, grade, classNum, number}
 * 같은 계정이 짧은 간격으로 여러 번 부르면 [WRITE_INTERVAL_MS] 안에는 한 번만 쓴다.
 */
@Service
class AccountDirectory(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    data class Account(
        val accountId: String,
        val name: String?,
        val grade: Int?,
        val classNum: Int?,
        val number: Int?,
        val lastSeen: Long,
    )

    private val lastWrite = ConcurrentHashMap<String, Long>()

    suspend fun touch(user: PlaceUser, now: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!user.isMember) return
        val id = accountId(user)
        val prev = lastWrite[id]
        if (!force && prev != null && now - prev < WRITE_INTERVAL_MS) return
        lastWrite[id] = now
        val info = objectMapper.writeValueAsString(
            mapOf("name" to user.name, "grade" to user.grade, "classNum" to user.classNum, "number" to user.number)
        )
        redis.opsForZSet().add(SEEN_KEY, id, now.toDouble()).awaitSingle()
        redis.opsForHash<String, String>().put(INFO_KEY, id, info).awaitSingle()
        // 토큰 수명(30일)보다 오래 안 들어온 계정은 목록에서 뺀다
        if (prev == null) {
            redis.opsForZSet().removeRangeByScore(SEEN_KEY, Range.closed(0.0, (now - RETAIN_MS).toDouble())).awaitSingle()
        }
    }

    suspend fun exists(accountId: String): Boolean =
        redis.opsForZSet().score(SEEN_KEY, accountId).awaitSingleOrNull() != null

    /** 최근 접속 순 */
    suspend fun list(limit: Long = MAX_LIST): List<Account> {
        val seen = redis.opsForZSet().reverseRangeWithScores(SEEN_KEY, Range.closed(0L, limit - 1))
            .collectList().awaitSingle()
        if (seen.isEmpty()) return emptyList()
        val ids = seen.map { it.value!! }
        val infos = redis.opsForHash<String, String>().multiGet(INFO_KEY, ids).awaitSingle()
        return seen.mapIndexed { i, t ->
            @Suppress("UNCHECKED_CAST")
            val info = infos.getOrNull(i)?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> } ?: emptyMap()
            Account(
                accountId = t.value!!,
                name = info["name"] as String?,
                grade = (info["grade"] as Number?)?.toInt(),
                classNum = (info["classNum"] as Number?)?.toInt(),
                number = (info["number"] as Number?)?.toInt(),
                lastSeen = t.score!!.toLong(),
            )
        }
    }

    companion object {
        const val SEEN_KEY = "accounts:seen"
        const val INFO_KEY = "accounts:info"
        private const val WRITE_INTERVAL_MS = 20_000L
        private const val RETAIN_MS = 30L * 24 * 3600 * 1000
        private const val MAX_LIST = 500L

        fun accountId(user: PlaceUser): String = user.id.removePrefix("m:")
    }
}
