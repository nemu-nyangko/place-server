package org.example.noneplace.domain.user.service

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService {
    fun resolveUserId(requestUserId: String?): String {
        return if (isValidUUID(requestUserId)) {
            requestUserId!!
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun isValidUUID(value: String?): Boolean {
        if (value == null) return false
        return try {
            UUID.fromString(value)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
