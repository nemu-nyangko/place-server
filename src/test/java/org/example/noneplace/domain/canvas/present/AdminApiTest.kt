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
import org.springframework.test.web.reactive.server.WebTestClient

/** 관리자 화면 API: 로그인 계정 목록, 관리자 지정/해제 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["auth.admin-ids=8146"])
@Import(TestRedisConfig::class)
@AutoConfigureWebTestClient
class AdminApiTest {
    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var authTokenService: AuthTokenService

    private fun token(id: String, name: String) =
        authTokenService.issueMember(PlaceUser("m:$id", UserType.MEMBER, name, 2, 1, 3))

    private fun me(token: String) =
        webTestClient.get().uri("/api/auth/me").header("Authorization", "Bearer $token").exchange()

    @Test
    fun `관리자는 로그인 계정을 보고 관리자를 지정·해제할 수 있다`() {
        val root = token("8146", "김동현")
        val friend = token("8200", "이친구")
        me(root).expectStatus().isOk
        me(friend).expectBody().jsonPath("$.admin").isEqualTo(false)

        // 일반 회원은 관리자 API 사용 불가
        webTestClient.get().uri("/api/admin/accounts").header("Authorization", "Bearer $friend").exchange()
            .expectStatus().isForbidden
        webTestClient.put().uri("/api/admin/admins/8200").header("Authorization", "Bearer $friend").exchange()
            .expectStatus().isForbidden
        webTestClient.get().uri("/api/admin/accounts").exchange().expectStatus().isUnauthorized

        // 목록: 최근 접속 순, 이름·학번·접속 중·관리자 여부
        webTestClient.get().uri("/api/admin/accounts").header("Authorization", "Bearer $root").exchange()
            .expectStatus().isOk.expectBody()
            .jsonPath("$.accounts[?(@.accountId=='8200')].name").isEqualTo("이친구")
            .jsonPath("$.accounts[?(@.accountId=='8200')].grade").isEqualTo(2)
            .jsonPath("$.accounts[?(@.accountId=='8200')].online").isEqualTo(true)
            .jsonPath("$.accounts[?(@.accountId=='8200')].admin").isEqualTo(false)
            .jsonPath("$.accounts[?(@.accountId=='8146')].fixedAdmin").isEqualTo(true)

        // 지정 → 바로 관리자(재로그인 불필요)
        webTestClient.put().uri("/api/admin/admins/8200").header("Authorization", "Bearer $root").exchange()
            .expectStatus().isOk
        me(friend).expectBody().jsonPath("$.admin").isEqualTo(true).jsonPath("$.unlimited").isEqualTo(true)

        // 지정된 관리자도 관리자 API 를 쓸 수 있다
        webTestClient.get().uri("/api/admin/accounts").header("Authorization", "Bearer $friend").exchange()
            .expectStatus().isOk

        // 로그인한 적 없는 번호, 잘못된 번호
        webTestClient.put().uri("/api/admin/admins/999999").header("Authorization", "Bearer $root").exchange()
            .expectStatus().isNotFound
        webTestClient.put().uri("/api/admin/admins/m:1").header("Authorization", "Bearer $root").exchange()
            .expectStatus().isBadRequest

        // 고정 관리자(ADMIN_IDS)는 해제 불가
        webTestClient.delete().uri("/api/admin/admins/8146").header("Authorization", "Bearer $friend").exchange()
            .expectStatus().isEqualTo(409)

        // 해제 → 일반 회원
        webTestClient.delete().uri("/api/admin/admins/8200").header("Authorization", "Bearer $root").exchange()
            .expectStatus().isOk
        me(friend).expectBody().jsonPath("$.admin").isEqualTo(false).jsonPath("$.maxStock").isEqualTo(30)
    }

    @Test
    fun `게스트는 계정 목록에 남지 않는다`() {
        val guest = authTokenService.issueGuest().second
        me(guest).expectStatus().isOk
        webTestClient.get().uri("/api/admin/accounts").header("Authorization", "Bearer ${token("8146", "김동현")}").exchange()
            .expectBody().jsonPath("$.accounts[?(@.accountId=~/g:.*/)]").isEmpty
    }
}
