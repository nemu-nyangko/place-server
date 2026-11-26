package org.example.noneplace.domain.user.service

import org.example.noneplace.domain.admin.service.AccountDirectory
import org.example.noneplace.domain.admin.service.AdminService
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
    private val adminService: AdminService,
    private val accountDirectory: AccountDirectory,
) {

    fun resolve(authorizationHeader: String?): PlaceUser? {
        val token = authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        val user = authTokenService.verify(token) ?: return null
        // 로그인 토큰을 가진 회원이 무엇이든 요청하면 관리자 화면의 계정 목록에 올라간다
        accountDirectory.touchInBackground(user)
        return if (user.isMember && adminService.isAdmin(user.id.removePrefix("m:"))) user.copy(admin = true) else user
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
