package org.example.noneplace.domain.user.service

import org.example.noneplace.domain.auth.config.AuthProperties
import org.example.noneplace.domain.auth.service.AuthTokenService
import org.example.noneplace.domain.user.model.PlaceUser
import org.springframework.stereotype.Service

/**
 * `Authorization: Bearer <token>` 헤더를 요청 주체로 해석한다.
 * 토큰이 없거나 위조/만료되었으면 null — 컨트롤러가 401 로 응답한다.
 */
@Service
class UserService(
    private val authTokenService: AuthTokenService,
    authProperties: AuthProperties,
) {
    /** 관리자 DataGSM 계정 id. 이름은 겹칠 수 있어 id 로만 지정한다 */
    private val adminIds: Set<String> = authProperties.adminIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun resolve(authorizationHeader: String?): PlaceUser? {
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        val user = authTokenService.verify(token) ?: return null
        return if (user.isMember && user.id.removePrefix("m:") in adminIds) user.copy(admin = true) else user
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
