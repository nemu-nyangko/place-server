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

/** AI 키가 없으면 가짜 리터치로 캔버스를 바꾸지 않고 503 을 준다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class AiRetouchUnavailableTest {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var authTokenService: AuthTokenService

    @Test
    fun `AI 미설정 시 리터치 503, me 의 aiAvailable false`() {
        val token = authTokenService.issueMember(PlaceUser("m:${System.nanoTime()}", UserType.MEMBER, "테스트"))
        webTestClient.post().uri("/api/canvas/ai-retouch")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("x" to 10, "y" to 10, "width" to 8, "height" to 8, "prompt" to ""))
            .exchange().expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.aiUnavailable").isEqualTo(true)
        webTestClient.get().uri("/api/auth/me").header("Authorization", "Bearer $token").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.aiAvailable").isEqualTo(false)
    }
}
