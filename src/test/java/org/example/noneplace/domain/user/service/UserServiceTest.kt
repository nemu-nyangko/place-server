package org.example.noneplace.domain.user.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class UserServiceTest {

    private val userService = UserService()

    @Test
    fun `유효한 UUID가 들어오면 그대로 반환`() {
        val uuid = UUID.randomUUID().toString()

        val result = userService.resolveUserId(uuid)

        assertEquals(uuid, result)
    }

    @Test
    fun `null이 들어오면 새 UUID 발급`() {
        val result = userService.resolveUserId(null)

        assertNotNull(result)
        assertDoesNotThrow { UUID.fromString(result) }
    }

    @Test
    fun `빈 문자열이 들어오면 새 UUID 발급`() {
        val result = userService.resolveUserId("")

        assertNotNull(result)
        assertDoesNotThrow { UUID.fromString(result) }
    }

    @Test
    fun `UUID 형식이 아닌 문자열이 들어오면 새 UUID 발급`() {
        val result = userService.resolveUserId("not-a-valid-uuid")

        assertNotNull(result)
        assertDoesNotThrow { UUID.fromString(result) }  // 새로 발급된 건 유효한 UUID여야 함
    }

    @Test
    fun `호출할 때마다 다른 UUID가 발급됨`() {
        val result1 = userService.resolveUserId(null)
        val result2 = userService.resolveUserId(null)

        assertNotEquals(result1, result2)
    }
}
