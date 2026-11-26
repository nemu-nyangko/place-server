package org.example.noneplace.domain.auth.present

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.example.noneplace.domain.admin.service.AccountDirectory
import org.example.noneplace.domain.canvas.service.AIRetouchService
import org.example.noneplace.domain.auth.config.AuthProperties
import org.example.noneplace.domain.auth.service.AuthTokenService
import org.example.noneplace.domain.auth.service.DataGsmOAuthService
import org.example.noneplace.domain.canvas.service.PixelStockService
import org.example.noneplace.domain.user.service.UserService
import org.example.noneplace.global.ratelimit.RateLimitService
import org.example.noneplace.global.web.ClientIp
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.*
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Tag(name = "Auth", description = "게스트 토큰 발급 / DataGSM 로그인")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authTokenService: AuthTokenService,
    private val oAuthService: DataGsmOAuthService,
    private val userService: UserService,
    private val pixelStockService: PixelStockService,
    private val rateLimitService: RateLimitService,
    private val authProperties: AuthProperties,
    private val aiRetouchService: AIRetouchService?,
    private val accountDirectory: AccountDirectory,
) {

    @Operation(summary = "게스트 토큰 발급", description = "QR 로 처음 들어온 사용자에게 바로 픽셀을 찍을 수 있는 게스트 토큰을 발급합니다.")
    @PostMapping("/guest")
    suspend fun issueGuest(request: ServerHttpRequest): ResponseEntity<Map<String, Any>> {
        val ip = ClientIp.of(request)
        if (!rateLimitService.tryAcquire("guest-issue:$ip", authProperties.guestIssuePerIpPerMinute, 60)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Too many guest sessions"))
        }
        val (user, token) = authTokenService.issueGuest()
        return ResponseEntity.ok(mapOf("token" to token, "userId" to user.id, "type" to user.type.name))
    }

    @Operation(summary = "내 정보", description = "토큰의 사용자 정보, 등급별 혜택, 현재 픽셀 스톡을 반환합니다.")
    @GetMapping("/me")
    suspend fun me(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Map<String, Any?>> {
        val user = userService.resolve(authorization)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Unauthorized"))
        val stock = pixelStockService.getStock(user)
        return ResponseEntity.ok(
            mapOf(
                "userId" to user.id,
                "type" to user.type.name,
                "name" to user.name,
                "grade" to user.grade,
                "classNum" to user.classNum,
                "number" to user.number,
                "admin" to user.isAdmin,
                "canAiRetouch" to user.isMember,
                "aiAvailable" to (aiRetouchService != null),
                "loginAvailable" to oAuthService.enabled,
            ) + stock.toMap()
        )
    }

    @Operation(summary = "DataGSM 로그인 시작", description = "DataGSM 인가 페이지로 리다이렉트합니다. 완료 후 return_to 주소로 #token=... 을 붙여 돌아갑니다.")
    @GetMapping("/datagsm/login")
    suspend fun login(@RequestParam("return_to", required = false) returnTo: String?): ResponseEntity<Void> {
        val target = returnTo?.takeIf { oAuthService.isAllowedReturnUrl(it) } ?: oAuthService.defaultReturnUrl()
        if (!oAuthService.enabled) return redirect(withFragment(target, "auth_error", "login_unavailable"))
        return redirect(oAuthService.createAuthorizationUrl(target))
    }

    @Operation(summary = "DataGSM 로그인 콜백", description = "DataGSM 에서 돌아오는 redirect_uri 입니다.")
    @GetMapping("/datagsm/callback")
    suspend fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Void> {
        val pending = oAuthService.consumeState(state)
            ?: return redirect(withFragment(oAuthService.defaultReturnUrl(), "auth_error", "invalid_state"))
        if (error != null || code.isNullOrBlank()) {
            return redirect(withFragment(pending.returnTo, "auth_error", error ?: "missing_code"))
        }
        return try {
            val user = oAuthService.authenticate(code, pending)
            accountDirectory.touch(user, force = true)
            redirect(withFragment(pending.returnTo, "token", authTokenService.issueMember(user)))
        } catch (e: DataGsmOAuthService.OAuthException) {
            redirect(withFragment(pending.returnTo, "auth_error", e.message ?: "login_failed"))
        }
    }

    private fun withFragment(url: String, key: String, value: String): String {
        val base = UriComponentsBuilder.fromUriString(url).fragment(null).build().toUriString()
        return "$base#$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }

    private fun redirect(url: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build()
}
