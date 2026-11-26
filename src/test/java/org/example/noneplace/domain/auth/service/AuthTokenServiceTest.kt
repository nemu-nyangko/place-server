package org.example.noneplace.domain.auth.service

import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestRedisConfig::class)
class AuthTokenServiceTest {

    @Autowired
    lateinit var authTokenService: AuthTokenService

    @Test
    fun `게스트 토큰은 검증되고 게스트 등급으로 해석`() {
        val (user, token) = authTokenService.issueGuest()
        val verified = authTokenService.verify(token)
        assertEquals(user.id, verified?.id)
        assertEquals(UserType.GUEST, verified?.type)
        assertTrue(verified!!.id.startsWith("g:"))
    }

    @Test
    fun `회원 토큰은 이름과 학번 정보를 유지`() {
        val token = authTokenService.issueMember(PlaceUser("m:42", UserType.MEMBER, "홍길동", 1, 2, 3))
        val verified = authTokenService.verify(token)!!
        assertEquals("m:42", verified.id)
        assertEquals("홍길동", verified.name)
        assertEquals(2, verified.classNum)
        assertTrue(verified.isMember)
    }

    @Test
    fun `서명이 변조된 토큰은 거부`() {
        val (_, token) = authTokenService.issueGuest()
        val body = token.substringBefore('.')
        // 게스트 페이로드에 다른 서명을 붙이거나, 페이로드를 바꾸면 실패해야 한다
        assertNull(authTokenService.verify("$body.AAAA"))
        val forgedBody = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"m:1","typ":"MEMBER","exp":9999999999}""".toByteArray())
        assertNull(authTokenService.verify("$forgedBody.${token.substringAfter('.')}"))
        assertNull(authTokenService.verify("garbage"))
        assertNull(authTokenService.verify(null))
    }
}
