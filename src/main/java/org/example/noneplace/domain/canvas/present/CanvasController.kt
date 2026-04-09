package org.example.noneplace.domain.canvas.present

import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.canvas.present.dto.PixelRequest
import org.example.noneplace.domain.canvas.service.CanvasService
import org.example.noneplace.domain.canvas.service.CooldownService
import org.example.noneplace.domain.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/canvas")
class CanvasController(
    private val canvasService: CanvasService,
    private val cooldownService: CooldownService,
    private val userService: UserService
) {

    @GetMapping(produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    suspend fun downloadCanvas(): ResponseEntity<ByteArray> {
        val data = canvasService.getCanvas() ?: ByteArray(512 * 512)
        return ResponseEntity.ok(data)
    }

    @GetMapping("/palette")
    fun getPalette(): ResponseEntity<List<Palette.Color>> {
        return ResponseEntity.ok(Palette.COLORS.toList())
    }

    @PostMapping("/pixel")
    suspend fun drawPixel(
        @RequestBody request: PixelRequest,
        @RequestHeader("X-User-Id", required = false) rawUserId: String?
    ): ResponseEntity<Map<String, Any>> {

        val userId = userService.resolveUserId(rawUserId)

        // 좌표 범위 검증
        if (request.x !in 0..511 || request.y !in 0..511) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Out of bounds"))
        }

        // 팔레트 검증
        if (!Palette.isValid(request.color)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid color index"))
        }

        // 쿨타임 체크
        val remaining = cooldownService.getRemainingCooldown(userId)
        if (remaining != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Cooldown active", "remainingSeconds" to remaining, "userId" to userId))
        }

        // 픽셀 업데이트
        val result = canvasService.updatePixel(request.x, request.y, request.color, userId)

        return if (result != null) {
            // 성공 시 쿨타임 등록
            cooldownService.setCooldown(userId)
            ResponseEntity.ok(mapOf("status" to "success", "offset" to result, "userId" to userId))
        } else {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }
}
