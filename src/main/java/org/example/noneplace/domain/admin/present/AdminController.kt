package org.example.noneplace.domain.admin.present

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.example.noneplace.domain.admin.service.AccountDirectory
import org.example.noneplace.domain.admin.service.AdminService
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Admin", description = "관리자 전용: 로그인 계정 확인, 관리자 지정/해제")
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val userService: UserService,
    private val adminService: AdminService,
    private val accountDirectory: AccountDirectory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(summary = "로그인한 계정 목록", description = "최근 접속 순. online 은 최근 2분 안에 접속(클라이언트가 1분마다 확인)한 계정.")
    @GetMapping("/accounts")
    suspend fun accounts(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Map<String, Any?>> {
        requireAdmin(authorization)?.let { return it }
        val now = System.currentTimeMillis()
        val accounts = accountDirectory.list().map {
            mapOf(
                "accountId" to it.accountId,
                "name" to it.name,
                "grade" to it.grade,
                "classNum" to it.classNum,
                "number" to it.number,
                "lastSeen" to it.lastSeen,
                "online" to (now - it.lastSeen <= ONLINE_WINDOW_MS),
                "admin" to adminService.isAdmin(it.accountId),
                "fixedAdmin" to adminService.isFixed(it.accountId),
            )
        }
        return ResponseEntity.ok(mapOf("accounts" to accounts, "now" to now))
    }

    @Operation(summary = "관리자 지정", description = "한 번이라도 로그인한 계정만 지정할 수 있습니다(번호 오타 방지).")
    @PutMapping("/admins/{accountId}")
    suspend fun grant(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable accountId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = userService.resolve(authorization)
        requireAdmin(actor)?.let { return it }
        if (!ACCOUNT_ID.matches(accountId)) return error(HttpStatus.BAD_REQUEST, "잘못된 계정 번호예요")
        if (!accountDirectory.exists(accountId)) return error(HttpStatus.NOT_FOUND, "로그인한 적 없는 계정이에요")
        adminService.grant(accountId)
        log.info("관리자 지정: {} by {}", accountId, actor!!.id)
        return ResponseEntity.ok(mapOf("accountId" to accountId, "admin" to true))
    }

    @Operation(summary = "관리자 해제", description = "서버 설정(ADMIN_IDS)으로 지정된 관리자는 해제할 수 없습니다.")
    @DeleteMapping("/admins/{accountId}")
    suspend fun revoke(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable accountId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = userService.resolve(authorization)
        requireAdmin(actor)?.let { return it }
        if (!ACCOUNT_ID.matches(accountId)) return error(HttpStatus.BAD_REQUEST, "잘못된 계정 번호예요")
        if (!adminService.revoke(accountId)) return error(HttpStatus.CONFLICT, "서버 설정으로 지정된 관리자라 여기서는 해제할 수 없어요")
        log.info("관리자 해제: {} by {}", accountId, actor!!.id)
        return ResponseEntity.ok(mapOf("accountId" to accountId, "admin" to false))
    }

    private fun requireAdmin(authorization: String?) = requireAdmin(userService.resolve(authorization))

    private fun requireAdmin(user: PlaceUser?): ResponseEntity<Map<String, Any?>>? = when {
        user == null -> error(HttpStatus.UNAUTHORIZED, "Unauthorized")
        !user.isAdmin -> error(HttpStatus.FORBIDDEN, "관리자만 쓸 수 있어요")
        else -> null
    }

    private fun error(status: HttpStatus, message: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(status).body(mapOf("error" to message))

    companion object {
        private val ACCOUNT_ID = Regex("^\\d{1,12}$")
        private const val ONLINE_WINDOW_MS = 120_000L
    }
}
