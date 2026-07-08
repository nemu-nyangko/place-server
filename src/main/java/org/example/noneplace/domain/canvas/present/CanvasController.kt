package org.example.noneplace.domain.canvas.present

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.canvas.present.dto.PixelRequest
import org.example.noneplace.domain.canvas.present.dto.RetouchRequest
import org.example.noneplace.domain.canvas.service.AIRetouchService
import org.example.noneplace.domain.canvas.service.CanvasService
import org.example.noneplace.domain.canvas.service.CooldownService
import org.example.noneplace.domain.canvas.service.PixelStockService
import org.example.noneplace.domain.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Canvas", description = "픽셀 캔버스 관련 API")
@RestController
@RequestMapping("/api/canvas")
class CanvasController(
    private val canvasService: CanvasService,
    private val cooldownService: CooldownService,
    private val pixelStockService: PixelStockService,
    private val userService: UserService,
    private val aiRetouchService: AIRetouchService
) {

    @Operation(summary = "픽셀 스톡 조회", description = "현재 사용자의 픽셀 스톡(충전) 상태를 반환합니다. 30초마다 1개씩, 최대 20개까지 충전됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "스톡 정보 반환 (stock, maxStock, secondsUntilNextCharge, userId)")
    )
    @GetMapping("/cooldown")
    suspend fun getCooldown(
        @Parameter(name = "X-User-Id", description = "사용자 UUID. 없으면 신규 발급.", `in` = ParameterIn.HEADER)
        @RequestHeader("X-User-Id", required = false) rawUserId: String?
    ): ResponseEntity<Map<String, Any>> {
        val userId = userService.resolveUserId(rawUserId)
        val stock = pixelStockService.getStock(userId)
        return ResponseEntity.ok()
            .header("X-User-Id", userId)
            .body(
                mapOf(
                    "stock" to stock.stock,
                    "maxStock" to stock.maxStock,
                    "secondsUntilNextCharge" to stock.secondsUntilNextCharge,
                    "userId" to userId
                )
            )
    }

    @Operation(summary = "캔버스 다운로드", description = "512×512 픽셀 캔버스를 바이너리(octet-stream)로 반환합니다. 각 바이트는 색상 인덱스(0~15)입니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "캔버스 바이너리 데이터 (262,144 바이트)")
    )
    @GetMapping(produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    suspend fun downloadCanvas(): ResponseEntity<ByteArray> {
        val data = canvasService.getCanvas() ?: ByteArray(512 * 512)
        return ResponseEntity.ok(data)
    }

    @Operation(summary = "팔레트 조회", description = "사용 가능한 16가지 색상 팔레트 목록을 반환합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "색상 목록 (index, name, hex)")
    )
    @GetMapping("/palette")
    fun getPalette(): ResponseEntity<List<Palette.Color>> {
        return ResponseEntity.ok(Palette.COLORS.toList())
    }

    @Operation(summary = "픽셀 그리기", description = "지정한 좌표에 색상을 찍습니다. 픽셀 스톡을 1개 소모하며, 스톡이 없으면 429를 반환합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공 (status, offset, stock, maxStock, secondsUntilNextCharge, userId)"),
        ApiResponse(responseCode = "400", description = "잘못된 좌표 또는 색상 인덱스"),
        ApiResponse(responseCode = "429", description = "스톡 부족 (stock, secondsUntilNextCharge 포함)")
    )
    @PostMapping("/pixel")
    suspend fun drawPixel(
        @RequestBody request: PixelRequest,
        @Parameter(name = "X-User-Id", description = "사용자 UUID. 없으면 신규 발급.", `in` = ParameterIn.HEADER)
        @RequestHeader("X-User-Id", required = false) rawUserId: String?
    ): ResponseEntity<Map<String, Any>> {

        val userId = userService.resolveUserId(rawUserId)

        if (request.x !in 0..511 || request.y !in 0..511) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Out of bounds"))
        }

        if (!Palette.isValid(request.color)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid color index"))
        }

        val stock = pixelStockService.consume(userId)
        if (!stock.consumed) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-User-Id", userId)
                .body(
                    mapOf(
                        "error" to "No stock",
                        "stock" to stock.stock,
                        "secondsUntilNextCharge" to stock.secondsUntilNextCharge,
                        "userId" to userId
                    )
                )
        }

        val result = canvasService.updatePixel(request.x, request.y, request.color, userId)

        return if (result != null) {
            ResponseEntity.ok()
                .header("X-User-Id", userId)
                .body(
                    mapOf(
                        "status" to "success",
                        "offset" to result,
                        "stock" to stock.stock,
                        "maxStock" to stock.maxStock,
                        "secondsUntilNextCharge" to stock.secondsUntilNextCharge,
                        "userId" to userId
                    )
                )
        } else {
            // 캔버스 쓰기 실패: 차감한 스톡을 best-effort 로 환불
            pixelStockService.refund(userId)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    @Operation(summary = "AI 리터치", description = "지정 영역(8×8~16×16)의 픽셀을 AI로 리터치합니다. 쿨타임(기본 15분) 동안 재요청이 제한됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공 (status, userId)"),
        ApiResponse(responseCode = "400", description = "영역 크기 범위 초과 또는 캔버스 경계 초과"),
        ApiResponse(responseCode = "429", description = "쿨타임 중 (remainingSeconds 포함)")
    )
    @PostMapping("/ai-retouch")
    suspend fun aiRetouch(
        @RequestBody request: RetouchRequest,
        @Parameter(name = "X-User-Id", description = "사용자 UUID. 없으면 신규 발급.", `in` = ParameterIn.HEADER)
        @RequestHeader("X-User-Id", required = false) rawUserId: String?
    ): ResponseEntity<Map<String, Any>> {
        val userId = userService.resolveUserId(rawUserId)

        if (request.width !in 8..16 || request.height !in 8..16) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Region size must be between 8 and 16"))
        }

        if (request.x !in 0..(511 - request.width + 1) || request.y !in 0..(511 - request.height + 1)) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Region exceeds canvas bounds"))
        }

        val remaining = cooldownService.getRemainingAiRetouchCooldown(userId)
        if (remaining != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Cooldown active", "remainingSeconds" to remaining, "userId" to userId))
        }

        val currentPixels = canvasService.getRegion(request.x, request.y, request.width, request.height)
        val retouchedPixels = aiRetouchService.retouch(request.x, request.y, request.width, request.height, currentPixels, request.prompt)
        canvasService.updateRegion(request.x, request.y, request.width, request.height, retouchedPixels, userId)
        cooldownService.setAiRetouchCooldown(userId)

        return ResponseEntity.ok()
            .header("X-User-Id", userId)
            .body(mapOf("status" to "success", "userId" to userId))
    }
}
