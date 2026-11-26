package org.example.noneplace.domain.canvas.present

import org.example.noneplace.domain.auth.service.AuthTokenService
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/** 관리자(auth.admin-ids)는 픽셀 스톡을 쓰지 않고, 이름이 같아도 id 가 다르면 관리자가 아니다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["auth.admin-ids=16"])
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class AdminTest {
    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var authTokenService: AuthTokenService

    private fun token(id: String) = authTokenService.issueMember(PlaceUser(id, UserType.MEMBER, "김동현"))

    private fun drawMany(token: String, n: Int, y: Int) =
        webTestClient.post().uri("/api/canvas/pixels")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("pixels" to (0 until n).map { mapOf("x" to it % 512, "y" to y + it / 512) }, "color" to 0))
            .exchange()

    @Test
    fun `관리자는 픽셀 무제한 - 30칸 넘게 여러 번 찍어도 스톡이 줄지 않음`() {
        val admin = token("m:16")
        webTestClient.get().uri("/api/auth/me").header("Authorization", "Bearer $admin").exchange()
            .expectBody().jsonPath("$.admin").isEqualTo(true).jsonPath("$.unlimited").isEqualTo(true)
        repeat(3) {
            drawMany(admin, 600, 100).expectStatus().isOk.expectBody()
                .jsonPath("$.placed").isEqualTo(600).jsonPath("$.unlimited").isEqualTo(true)
        }
        // 한 칸 찍기도 무제한
        repeat(40) {
            webTestClient.post().uri("/api/canvas/pixel").header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("x" to it, "y" to 300, "color" to 3))
                .exchange().expectStatus().isOk
        }
        drawMany(admin, 1001, 200).expectStatus().isBadRequest.expectBody().jsonPath("$.max").isEqualTo(1000)
    }

    @Test
    fun `이름이 같아도 다른 계정은 일반 회원`() {
        val other = token("m:99")
        webTestClient.get().uri("/api/auth/me").header("Authorization", "Bearer $other").exchange()
            .expectBody().jsonPath("$.admin").isEqualTo(false).jsonPath("$.maxStock").isEqualTo(30)
        drawMany(other, 31, 400).expectStatus().isBadRequest
    }
}
