package org.example.noneplace.domain.canvas.present

import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.http.MediaType
import java.util.UUID
import kotlin.test.Test

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class CanvasControllerTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    private lateinit var userId: String

    @BeforeEach
    fun setUp() {
        userId = UUID.randomUUID().toString()  // 테스트마다 새 UUID로 쿨타임 충돌 방지
    }

    @Test
    fun `바이너리 캔버스 다운로드 API 테스트`() {
        webTestClient.get()
            .uri("/api/canvas")
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .expectBody(ByteArray::class.java)
            .consumeWith { result ->
                val body = result.responseBody
                assertNotNull(body)
                assertEquals(512 * 512, body?.size)
            }
    }

    @Test
    fun `팔레트 조회 API 테스트`() {
        webTestClient.get()
            .uri("/api/canvas/palette")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(16)
            .jsonPath("$[0].index").isEqualTo(0)
            .jsonPath("$[0].name").isEqualTo("White")
            .jsonPath("$[0].hex").isEqualTo("#FFFFFF")
    }

    @Test
    fun `픽셀 찍기 성공 - userId 와 스톡 반환 확인`() {
        val request = mapOf("x" to 10, "y" to 10, "color" to 1)

        webTestClient.post()
            .uri("/api/canvas/pixel")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("success")
            .jsonPath("$.userId").isEqualTo(userId)
            .jsonPath("$.stock").isEqualTo(19)   // 20개 중 1개 소모
            .jsonPath("$.maxStock").isEqualTo(20)
    }

    @Test
    fun `X-User-Id 없이 요청 시 신규 UUID 발급`() {
        val request = mapOf("x" to 20, "y" to 20, "color" to 2)

        webTestClient.post()
            .uri("/api/canvas/pixel")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("success")
            .jsonPath("$.userId").exists()  // UUID가 새로 발급됐는지 확인
    }

    @Test
    fun `스톡 20개를 모두 소진하면 429 반환`() {
        val request = mapOf("x" to 30, "y" to 30, "color" to 3)

        // 20회까지는 성공
        repeat(20) {
            webTestClient.post()
                .uri("/api/canvas/pixel")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk
        }

        // 21번째 요청 - 스톡 부족으로 429
        webTestClient.post()
            .uri("/api/canvas/pixel")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.error").isEqualTo("No stock")
            .jsonPath("$.stock").isEqualTo(0)
            .jsonPath("$.secondsUntilNextCharge").exists()
    }

    @Test
    fun `범위 밖 좌표로 요청 시 400 반환`() {
        val request = mapOf("x" to 999, "y" to 999, "color" to 1)

        webTestClient.post()
            .uri("/api/canvas/pixel")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("Out of bounds")
    }

    @Test
    fun `유효하지 않은 색상 인덱스로 요청 시 400 반환`() {
        val request = mapOf("x" to 10, "y" to 10, "color" to 99)

        webTestClient.post()
            .uri("/api/canvas/pixel")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("Invalid color index")
    }

    @Test
    fun `신규 유저 스톡 조회 시 가득 찬 20개 반환`() {
        webTestClient.get()
            .uri("/api/canvas/cooldown")
            .header("X-User-Id", userId)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stock").isEqualTo(20)
            .jsonPath("$.maxStock").isEqualTo(20)
            .jsonPath("$.secondsUntilNextCharge").isEqualTo(0)
            .jsonPath("$.userId").isEqualTo(userId)
    }

    @Test
    fun `픽셀 찍은 후 스톡이 19개로 감소`() {
        val request = mapOf("x" to 50, "y" to 50, "color" to 1)

        webTestClient.post()
            .uri("/api/canvas/pixel")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk

        webTestClient.get()
            .uri("/api/canvas/cooldown")
            .header("X-User-Id", userId)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.stock").isEqualTo(19)
            .jsonPath("$.maxStock").isEqualTo(20)
            .jsonPath("$.userId").isEqualTo(userId)
    }
}
