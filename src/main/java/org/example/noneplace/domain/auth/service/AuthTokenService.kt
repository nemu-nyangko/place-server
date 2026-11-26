package org.example.noneplace.domain.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.example.noneplace.domain.auth.config.AuthProperties
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 서버 자체 액세스 토큰 발급/검증.
 * 형식: base64url(payload JSON) + "." + base64url(HMAC-SHA256(payload))
 * 상태를 Redis 에 두지 않아 요청마다 저장소 조회가 없다.
 */
@Service
class AuthTokenService(
    private val properties: AuthProperties,
    private val objectMapper: ObjectMapper,
) {
    private val key = SecretKeySpec(properties.tokenSecret.toByteArray(), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    init {
        require(properties.tokenSecret.length >= 32) { "auth.token-secret 은 32자 이상이어야 합니다." }
    }

    data class Payload(
        val sub: String,
        val typ: UserType,
        val exp: Long,
        val name: String? = null,
        val grade: Int? = null,
        val cls: Int? = null,
        val num: Int? = null,
    )

    fun issueGuest(): Pair<PlaceUser, String> {
        val user = PlaceUser(id = "g:${UUID.randomUUID()}", type = UserType.GUEST)
        return user to issue(user, Duration.ofDays(properties.guestTokenTtlDays))
    }

    fun issueMember(user: PlaceUser): String = issue(user, Duration.ofDays(properties.memberTokenTtlDays))

    private fun issue(user: PlaceUser, ttl: Duration): String {
        val payload = Payload(
            sub = user.id,
            typ = user.type,
            exp = Instant.now().plus(ttl).epochSecond,
            name = user.name,
            grade = user.grade,
            cls = user.classNum,
            num = user.number,
        )
        val body = encoder.encodeToString(objectMapper.writeValueAsBytes(payload))
        return "$body.${sign(body)}"
    }

    fun verify(token: String?): PlaceUser? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        val (body, signature) = parts
        if (!MessageDigest.isEqual(sign(body).toByteArray(), signature.toByteArray())) return null
        val payload = try {
            objectMapper.readValue<Payload>(decoder.decode(body))
        } catch (e: Exception) {
            return null
        }
        if (payload.exp < Instant.now().epochSecond) return null
        val prefixOk = when (payload.typ) {
            UserType.GUEST -> payload.sub.startsWith("g:")
            UserType.MEMBER -> payload.sub.startsWith("m:")
        }
        if (!prefixOk) return null
        return PlaceUser(payload.sub, payload.typ, payload.name, payload.grade, payload.cls, payload.num)
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return encoder.encodeToString(mac.doFinal(body.toByteArray()))
    }
}
