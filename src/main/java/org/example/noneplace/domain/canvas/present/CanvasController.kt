package org.example.noneplace.domain.canvas.present

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.example.noneplace.domain.canvas.config.CanvasProperties
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.canvas.present.dto.PixelRequest
import org.example.noneplace.domain.canvas.present.dto.RetouchRequest
import org.example.noneplace.domain.canvas.service.AIRetouchService
import org.example.noneplace.domain.canvas.service.CanvasMirror
import org.example.noneplace.domain.canvas.service.CanvasService
import org.example.noneplace.domain.canvas.service.CooldownService
import org.example.noneplace.domain.canvas.service.PixelStockService
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.service.UserService
import org.example.noneplace.global.ratelimit.RateLimitService
import org.example.noneplace.global.web.ClientIp
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.*

@Tag(name = "Canvas", description = "픽셀 캔버스 관련 API")
@RestController
@RequestMapping("/api/canvas")
class CanvasController(
    private val canvasService: CanvasService,
    private val mirror: CanvasMirror,
    private val cooldownService: CooldownService,
    private val pixelStockService: PixelStockService,
    private val userService: UserService,
    private val aiRetouchService: AIRetouchService,
    private val rateLimitService: RateLimitService,
    private val properties: CanvasProperties,
) {

    @Operation(summary = "캔버스 다운로드", description = "512×512 캔버스를 바이너리로 반환합니다. 각 바이트는 색상 인덱스(0~15). Accept-Encoding: gzip 이면 압축해 보냅니다.")
    @GetMapping(produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    suspend fun downloadCanvas(request: ServerHttpRequest): ResponseEntity<ByteArray> {
        val noCache = CacheControl.noStore()
        if (!mirror.ready) {
            return ResponseEntity.ok().cacheControl(noCache).body(canvasService.loadCanvasFromRedis())
        }
        val acceptsGzip = request.headers.getFirst(HttpHeaders.ACCEPT_ENCODING)?.contains("gzip") == true
        return if (acceptsGzip) {
            ResponseEntity.ok()
                .cacheControl(noCache)
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .body(mirror.gzipped())
        } else {
            ResponseEntity.ok().cacheControl(noCache).body(mirror.snapshot())
        }
    }

    @Operation(summary = "팔레트 조회", description = "사용 가능한 16가지 색상 팔레트 목록을 반환합니다.")
    @GetMapping("/palette")
    fun getPalette(): ResponseEntity<List<Palette.Color>> = ResponseEntity.ok(Palette.COLORS.toList())

    @Operation(summary = "픽셀 스톡 조회", description = "현재 사용자의 픽셀 스톡 상태. 게스트/회원 등급에 따라 최대치와 충전 속도가 다릅니다.")
    @GetMapping("/cooldown")
    suspend fun getCooldown(
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val user = userService.resolve(authorization) ?: return unauthorized()
        return ResponseEntity.ok(pixelStockService.getStock(user).toMap() + ("userId" to user.id))
    }

    @Operation(summary = "픽셀 정보", description = "해당 좌표를 마지막으로 찍은 사람과 시각을 반환합니다.")
    @GetMapping("/pixel")
    suspend fun getPixelInfo(@RequestParam x: Int, @RequestParam y: Int): ResponseEntity<Map<String, Any?>> {
        val info = canvasService.getPixelInfo(x, y)
        return ResponseEntity.ok(
            mapOf(
                "x" to x,
                "y" to y,
                "color" to mirror.region(x, y, 1, 1)[0].toInt(),
                "name" to info?.name,
                "member" to info?.member,
                "placedAt" to info?.placedAt,
            )
        )
    }

    @Operation(summary = "픽셀 그리기", description = "지정한 좌표에 색상을 찍습니다. 픽셀 스톡을 1개 소모하며, 스톡이 없으면 429를 반환합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공 (status, offset, stock, maxStock, regenSeconds, secondsUntilNextCharge)"),
        ApiResponse(responseCode = "400", description = "잘못된 좌표 또는 색상 인덱스"),
        ApiResponse(responseCode = "401", description = "토큰 없음/만료"),
        ApiResponse(responseCode = "429", description = "스톡 부족 또는 IP 단위 게스트 제한"),
    )
    @PostMapping("/pixel")
    suspend fun drawPixel(
        @RequestBody request: PixelRequest,
        @RequestHeader("Authorization", required = false) authorization: String?,
        httpRequest: ServerHttpRequest,
    ): ResponseEntity<Map<String, Any>> {
        val user = userService.resolve(authorization) ?: return unauthorized()

        if (request.x !in 0..511 || request.y !in 0..511) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Out of bounds"))
        }
        if (!Palette.isValid(request.color)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid color index"))
        }
        if (!user.isMember && !guestIpAllowed(httpRequest)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Too many guest pixels from this network", "loginRequired" to true))
        }

        val stock = canvasService.placePixel(user, request.x, request.y, request.color)
        if (!stock.consumed) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "No stock") + stock.toMap())
        }
        return ResponseEntity.ok(
            mapOf("status" to "success", "offset" to request.y * 512 + request.x) + stock.toMap()
        )
    }

    @Operation(summary = "AI 리터치 (회원 전용)", description = "지정 영역(8×8~16×16)의 픽셀을 AI로 리터치합니다. DataGSM 로그인 회원만 사용할 수 있습니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "영역 크기 범위 초과 또는 캔버스 경계 초과"),
        ApiResponse(responseCode = "403", description = "게스트는 사용할 수 없음"),
        ApiResponse(responseCode = "429", description = "쿨타임 중 (remainingSeconds 포함)"),
    )
    @PostMapping("/ai-retouch")
    suspend fun aiRetouch(
        @RequestBody request: RetouchRequest,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val user = userService.resolve(authorization) ?: return unauthorized()
        if (!user.isMember) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Login required", "loginRequired" to true))
        }
        if (request.width !in 8..16 || request.height !in 8..16) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Region size must be between 8 and 16"))
        }
        if (request.x !in 0..(512 - request.width) || request.y !in 0..(512 - request.height)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Region exceeds canvas bounds"))
        }
        if (request.prompt.length > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Prompt too long"))
        }

        val remaining = cooldownService.claimAiRetouch(user.id)
        if (remaining != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Cooldown active", "remainingSeconds" to remaining))
        }

        try {
            val current = canvasService.getRegion(request.x, request.y, request.width, request.height)
            val retouched = aiRetouchService.retouch(request.x, request.y, request.width, request.height, current, request.prompt)
            canvasService.updateRegion(user, request.x, request.y, request.width, request.height, retouched)
        } catch (e: Exception) {
            cooldownService.releaseAiRetouch(user.id)
            throw e
        }
        return ResponseEntity.ok(
            mapOf("status" to "success", "cooldownSeconds" to properties.aiRetouchCooldownSeconds)
        )
    }

    @Operation(summary = "AI 리터치 쿨타임 조회")
    @GetMapping("/ai-retouch/cooldown")
    suspend fun aiRetouchCooldown(
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val user = userService.resolve(authorization) ?: return unauthorized()
        val remaining = if (user.isMember) cooldownService.getRemainingAiRetouchCooldown(user.id) ?: 0 else 0
        return ResponseEntity.ok(mapOf("available" to user.isMember, "remainingSeconds" to remaining))
    }

    private suspend fun guestIpAllowed(request: ServerHttpRequest): Boolean =
        rateLimitService.tryAcquire("guest-px:${ClientIp.of(request)}", properties.guestPixelsPerIpPerMinute, 60)

    private fun <T> unauthorized(): ResponseEntity<T> {
        @Suppress("UNCHECKED_CAST")
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Unauthorized") as T)
    }

    companion object {
        private const val MAX_PROMPT_LENGTH = 300
    }
}
