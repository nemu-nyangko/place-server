package org.example.noneplace.domain.auth.present

import org.example.noneplace.domain.auth.service.AuthTokenService
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * DataGSM 을 흉내 내는 가짜 OAuth 서버로 로그인 전체 흐름(인가 → 콜백 → 토큰 교환 → userinfo → 자체 토큰)을 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class AuthControllerTest {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var authTokenService: AuthTokenService

    companion object {
        @Volatile var lastTokenRequestBody: String = ""

        private val fakeDataGsm: DisposableServer = HttpServer.create()
            .port(0)
            .route { routes ->
                routes.post("/v1/oauth/token") { req, res ->
                    req.receive().aggregate().asString().flatMap { body ->
                        lastTokenRequestBody = body
                        // 실제 DataGSM 처럼 JSON 본문만 인식
                        if (req.requestHeaders().get("Content-Type")?.startsWith("application/json") != true) {
                            return@flatMap res.status(400).header("Content-Type", "application/json")
                                .sendString(Mono.just("""{"error":"invalid_request","error_description":"grant_type은 필수입니다."}"""))
                                .then()
                        }
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just("""{"access_token":"fake-access","token_type":"Bearer","expires_in":3600}"""))
                            .then()
                    }
                }
                routes.get("/userinfo") { req, res ->
                    if (req.requestHeaders().get("Authorization") != "Bearer fake-access") {
                        res.status(401).send()
                    } else {
                        res.header("Content-Type", "application/json").sendString(
                            Mono.just(
                                """{"sub":"s@gsm.hs.kr","id":77,"email":"s@gsm.hs.kr","role":"USER","status":"ACTIVE",
                                "objectType":"STUDENT","student":{"name":"홍길동","grade":1,"classNum":2,"number":3,"role":"GENERAL_STUDENT"},
                                "teacher":null,"clubs":[],"projects":[]}"""
                            )
                        )
                    }
                }
            }
            .bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val base = "http://127.0.0.1:${fakeDataGsm.port()}"
            registry.add("auth.datagsm.client-id") { "test-client" }
            registry.add("auth.datagsm.client-secret") { "test-secret" }
            registry.add("auth.datagsm.redirect-uri") { "http://localhost:8080/api/auth/datagsm/callback" }
            registry.add("auth.datagsm.authorize-url") { "$base/v1/oauth/authorize" }
            registry.add("auth.datagsm.token-url") { "$base/v1/oauth/token" }
            registry.add("auth.datagsm.userinfo-url") { "$base/userinfo" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = fakeDataGsm.disposeNow()
    }

    private fun location(uri: String): String =
        webTestClient.get().uri(uri).exchange()
            .expectStatus().isFound
            .returnResult(Void::class.java).responseHeaders.location!!.toString()

    @Test
    fun `DataGSM 로그인 전체 흐름 - 회원 토큰을 클라이언트 fragment 로 전달`() {
        val authorize = location("/api/auth/datagsm/login?return_to=http://localhost:5173/?x=1")
        val params = UriComponentsBuilder.fromUriString(authorize).build().queryParams
        assertEquals("test-client", params.getFirst("client_id"))
        assertEquals("S256", params.getFirst("code_challenge_method"))
        assertNotNull(params.getFirst("code_challenge"))
        val state = params.getFirst("state")!!

        val back = location("/api/auth/datagsm/callback?code=abc&state=$state")
        assertTrue(back.startsWith("http://localhost:5173/?x=1#token="), back)
        val token = URLDecoder.decode(back.substringAfter("#token="), StandardCharsets.UTF_8)
        val user = authTokenService.verify(token)!!
        assertEquals("m:77", user.id)
        assertEquals("홍길동", user.name)
        assertEquals(1, user.grade)

        // PKCE verifier 와 client secret 을 함께 보냈는지
        assertTrue(lastTokenRequestBody.contains("\"code_verifier\":\""))
        assertTrue(lastTokenRequestBody.contains("\"client_secret\":\"test-secret\""))
        assertTrue(lastTokenRequestBody.contains("\"grant_type\":\"authorization_code\""))

        // state 는 1회용
        val reused = location("/api/auth/datagsm/callback?code=abc&state=$state")
        assertTrue(reused.contains("#auth_error=invalid_state"), reused)
    }

    @Test
    fun `허용되지 않은 return_to 는 기본 클라이언트 주소로 대체 (오픈 리다이렉트 방지)`() {
        val authorize = location("/api/auth/datagsm/login?return_to=https://evil.example.com/")
        val state = UriComponentsBuilder.fromUriString(authorize).build().queryParams.getFirst("state")!!
        val back = location("/api/auth/datagsm/callback?error=access_denied&state=$state")
        assertTrue(back.startsWith("http://localhost:5173"), back)
        assertTrue(back.contains("auth_error=access_denied"))
    }

    @Test
    fun `게스트 토큰 발급`() {
        webTestClient.post().uri("/api/auth/guest").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.type").isEqualTo("GUEST").jsonPath("$.token").exists()
    }
}
