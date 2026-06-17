package org.example.noneplace.global.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest()
            .body(mapOf("error" to (e.message ?: "잘못된 요청입니다.")))

    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInput(e: ServerWebInputException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest()
            .body(mapOf("error" to "요청 형식이 올바르지 않습니다."))

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "서버 오류가 발생했습니다."))
}
