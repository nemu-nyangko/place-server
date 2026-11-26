package org.example.noneplace.domain.admin.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.example.noneplace.domain.auth.config.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * 관리자 목록.
 * - 고정 관리자: 서버 설정 `auth.admin-ids`(ADMIN_IDS). 사이트에서 해제할 수 없다.
 * - 지정 관리자: 관리자가 사이트에서 지정한 계정. Redis Set 에 저장하고, 요청마다 Redis 를 읽지 않도록
 *   메모리에 들고 있다가 [REFRESH_SECONDS]초마다(그리고 지정/해제 직후) 다시 읽는다.
 * id 는 `m:` 를 뺀 DataGSM 계정 번호. 이름은 겹칠 수 있어 쓰지 않는다.
 */
@Service
class AdminService(
    private val redis: ReactiveStringRedisTemplate,
    authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val fixedIds: Set<String> = authProperties.adminIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Volatile
    private var grantedIds: Set<String> = emptySet()
    private var refresher: Disposable? = null

    fun isAdmin(accountId: String): Boolean = accountId in fixedIds || accountId in grantedIds
    fun isFixed(accountId: String): Boolean = accountId in fixedIds

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        refresher = Flux.interval(Duration.ZERO, Duration.ofSeconds(REFRESH_SECONDS))
            .concatMap { mono { refresh() }.onErrorResume { e -> log.warn("관리자 목록 갱신 실패: {}", e.message); Mono.empty() } }
            .subscribe()
    }

    /** Redis 연결이 닫히기 전에 멈춘다 */
    @EventListener(ContextClosedEvent::class)
    fun stop() {
        refresher?.dispose()
    }

    suspend fun refresh() {
        grantedIds = redis.opsForSet().members(KEY).collectList().awaitSingle().toSet()
    }

    suspend fun grant(accountId: String) {
        redis.opsForSet().add(KEY, accountId).awaitSingle()
        refresh()
    }

    /** 고정 관리자는 해제 불가 → false */
    suspend fun revoke(accountId: String): Boolean {
        if (isFixed(accountId)) return false
        redis.opsForSet().remove(KEY, accountId).awaitSingle()
        refresh()
        return true
    }

    companion object {
        const val KEY = "admins"
        private const val REFRESH_SECONDS = 5L
    }
}
