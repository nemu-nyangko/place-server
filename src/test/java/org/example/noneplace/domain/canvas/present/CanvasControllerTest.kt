package org.example.noneplace.domain.canvas.present

import org.example.noneplace.domain.auth.service.AuthTokenService
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.zip.GZIPInputStream

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class CanvasControllerTest {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var authTokenService: AuthTokenService

    private fun guestToken(): String =
        webTestClient.post().uri("/api/auth/guest").exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["token"] as String

    private fun memberToken(name: String = "김지에스엠"): String =
        authTokenService.issueMember(PlaceUser("m:${System.nanoTime()}", UserType.MEMBER, name, 2, 1, 7))

    private fun draw(token: String?, x: Int, y: Int, color: Int = 3) =
        webTestClient.post().uri("/api/canvas/pixel")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("x" to x, "y" to y, "color" to color))
            .exchange()

    @Test
    fun `캔버스 다운로드 - gzip 압축 응답도 512x512`() {
        val body = webTestClient.get().uri("/api/canvas")
            .header("Accept-Encoding", "gzip")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Content-Encoding", "gzip")
            .expectBody(ByteArray::class.java).returnResult().responseBody!!
        assertEquals(512 * 512, GZIPInputStream(body.inputStream()).readBytes().size)
    }

    @Test
    fun `캔버스 다운로드 - 비압축`() {
        webTestClient.get().uri("/api/canvas")
            .exchange()
            .expectStatus().isOk
            .expectBody(ByteArray::class.java)
            .consumeWith { assertEquals(512 * 512, it.responseBody?.size) }
    }

    @Test
    fun `팔레트 조회`() {
        webTestClient.get().uri("/api/canvas/palette").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(16)
            .jsonPath("$[0].hex").isEqualTo("#FFFFFF")
    }

    @Test
    fun `토큰 없이 찍으면 401`() {
        draw(null, 1, 1).expectStatus().isUnauthorized
        draw("forged.token", 1, 1).expectStatus().isUnauthorized
    }

    @Test
    fun `게스트는 5개 찍고 나면 429`() {
        val token = guestToken()
        for (i in 0 until 5) {
            draw(token, 20 + i, 30).expectStatus().isOk
                .expectBody().jsonPath("$.stock").isEqualTo(4 - i).jsonPath("$.maxStock").isEqualTo(5)
        }
        draw(token, 40, 30).expectStatus().isEqualTo(429)
            .expectBody().jsonPath("$.error").isEqualTo("No stock").jsonPath("$.stock").isEqualTo(0)
    }

    @Test
    fun `회원은 30개 스톡과 빠른 충전`() {
        val token = memberToken()
        webTestClient.get().uri("/api/auth/me").header("Authorization", "Bearer $token").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.type").isEqualTo("MEMBER")
            .jsonPath("$.maxStock").isEqualTo(30)
            .jsonPath("$.regenSeconds").isEqualTo(15)
            .jsonPath("$.canAiRetouch").isEqualTo(true)
        draw(token, 50, 50).expectStatus().isOk.expectBody().jsonPath("$.stock").isEqualTo(29)
    }

    @Test
    fun `좌표, 색상 검증`() {
        val token = guestToken()
        draw(token, 512, 0).expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("Out of bounds")
        draw(token, 0, 0, 16).expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("Invalid color index")
    }

    @Test
    fun `픽셀 정보에 회원 이름 표시`() {
        draw(memberToken("박픽셀"), 77, 88, 6).expectStatus().isOk
        webTestClient.get().uri("/api/canvas/pixel?x=77&y=88").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("박픽셀")
            .jsonPath("$.member").isEqualTo(true)
    }

    @Test
    fun `AI 리터치는 게스트 403, 회원은 성공 후 쿨타임 429`() {
        val req = mapOf("x" to 400, "y" to 400, "width" to 8, "height" to 8, "prompt" to "")
        webTestClient.post().uri("/api/canvas/ai-retouch")
            .header("Authorization", "Bearer ${guestToken()}")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
            .exchange().expectStatus().isForbidden

        val token = memberToken()
        webTestClient.post().uri("/api/canvas/ai-retouch")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
            .exchange().expectStatus().isOk
        webTestClient.post().uri("/api/canvas/ai-retouch")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
            .exchange().expectStatus().isEqualTo(429)
    }

    @Test
    fun `AI 리터치 영역 검증`() {
        val token = memberToken()
        webTestClient.post().uri("/api/canvas/ai-retouch")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("x" to 500, "y" to 0, "width" to 16, "height" to 16))
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.error").isEqualTo("Region exceeds canvas bounds")
    }
}
