package org.example.noneplace.domain.auth.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.noneplace.domain.auth.config.AuthProperties
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.global.config.CorsConfig
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * DataGSM OAuth 2.0 (Authorization Code + PKCE) 로그인.
 * https://docs.datagsm.kr/oauth
 *
 * 토큰 교환은 반드시 서버에서 수행(클라이언트 시크릿 보호, 토큰 엔드포인트 CORS 제한).
 * 로그인 시작 시 state 와 code_verifier 를 Redis 에 10분간 보관한다.
 */
@Service
class DataGsmOAuthService(
    private val properties: AuthProperties,
    private val corsConfig: CorsConfig,
    private val stringRedisTemplate: ReactiveStringRedisTemplate,
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val dataGsm get() = properties.datagsm

    class OAuthException(message: String) : RuntimeException(message)

    data class PendingLogin(val verifier: String, val returnTo: String)

    val enabled: Boolean get() = dataGsm.enabled

    /** 로그인 후 돌아갈 주소가 허용된 클라이언트 오리진인지 확인 (오픈 리다이렉트 방지) */
    fun isAllowedReturnUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { URI(url) } catch (e: Exception) { return false }
        if (uri.scheme == null || uri.host == null) return false
        val origin = buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) append(":").append(uri.port)
        }
        return corsConfig.allowedOrigins.any { it.trimEnd('/') == origin }
    }

    fun defaultReturnUrl(): String = corsConfig.allowedOrigins.firstOrNull() ?: "/"

    suspend fun createAuthorizationUrl(returnTo: String): String {
        val state = randomToken()
        val verifier = randomToken()
        val challenge = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        stringRedisTemplate.opsForValue()
            .set(stateKey(state), objectMapper.writeValueAsString(PendingLogin(verifier, returnTo)), STATE_TTL)
            .awaitSingleOrNull()

        return UriComponentsBuilder.fromUriString(dataGsm.authorizeUrl)
            .queryParam("client_id", dataGsm.clientId)
            .queryParam("redirect_uri", dataGsm.redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", dataGsm.scope)
            .queryParam("state", state)
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .encode()
            .build()
            .toUriString()
    }

    /** state 를 1회성으로 소비하고 보관해 둔 로그인 정보를 돌려준다 */
    suspend fun consumeState(state: String?): PendingLogin? {
        if (state.isNullOrBlank()) return null
        val key = stateKey(state)
        val raw = stringRedisTemplate.opsForValue().get(key).awaitSingleOrNull() ?: return null
        stringRedisTemplate.delete(key).awaitSingleOrNull()
        return try { objectMapper.readValue<PendingLogin>(raw) } catch (e: Exception) { null }
    }

    /** 인가 코드를 토큰으로 교환하고 사용자 정보를 받아 회원으로 변환 */
    suspend fun authenticate(code: String, pending: PendingLogin): PlaceUser {
        // DataGSM 토큰 엔드포인트는 실제로 JSON 본문만 읽는다(폼 전송 시 "grant_type은 필수입니다" 400)
        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to dataGsm.clientId,
            "client_secret" to dataGsm.clientSecret,
            "redirect_uri" to dataGsm.redirectUri,
            "code_verifier" to pending.verifier,
        )
        val token = try {
            webClient.post()
                .uri(dataGsm.tokenUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .awaitBody<TokenResponse>()
        } catch (e: WebClientResponseException) {
            // DataGSM 오류 본문({error, error_description})에는 비밀값이 없으므로 원인 파악용으로 남긴다
            log.warn("DataGSM token exchange failed: {} {}", e.statusCode, e.responseBodyAsString.take(500))
            throw OAuthException("token_exchange_failed")
        } catch (e: Exception) {
            log.warn("DataGSM token exchange failed: {}", e.message)
            throw OAuthException("token_exchange_failed")
        }

        val info = try {
            webClient.get()
                .uri(dataGsm.userinfoUrl)
                .header("Authorization", "Bearer ${token.accessToken}")
                .retrieve()
                .awaitBody<UserInfo>()
        } catch (e: WebClientResponseException) {
            log.warn("DataGSM userinfo failed: {} {}", e.statusCode, e.responseBodyAsString.take(500))
            throw OAuthException("userinfo_failed")
        } catch (e: Exception) {
            log.warn("DataGSM userinfo failed: {}", e.message)
            throw OAuthException("userinfo_failed")
        }

        // DataGSM 로그인(@gsm.hs.kr, 활성 계정만 가능) 자체가 우리 학교 구성원이라는 증명 — 별도 판별 없음
        val name = info.student?.name ?: info.teacher?.name?.let { "$it 선생님" } ?: info.email?.substringBefore('@')
        return PlaceUser(
            id = "m:${info.id}",
            type = UserType.MEMBER,
            name = name,
            grade = info.student?.grade,
            classNum = info.student?.classNum,
            number = info.student?.number,
        )
    }

    private fun randomToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { b64.encodeToString(it) }

    private fun stateKey(state: String) = "oauth-state:$state"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("token_type") val tokenType: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserInfo(
        val id: Long,
        val email: String? = null,
        val student: Student? = null,
        val teacher: Teacher? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Student(
        val name: String? = null,
        val grade: Int? = null,
        val classNum: Int? = null,
        val number: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Teacher(val name: String? = null)

    companion object {
        private val STATE_TTL: Duration = Duration.ofMinutes(10)
    }
}
