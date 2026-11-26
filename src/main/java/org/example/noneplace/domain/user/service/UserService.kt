package org.example.noneplace.domain.user.service

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
) {
    fun resolve(authorizationHeader: String?): PlaceUser? {
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        return authTokenService.verify(token)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
