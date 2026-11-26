package org.example.noneplace.global.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
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

    /** 없는 경로(404), 허용되지 않은 메서드(405) 등 프레임워크가 정한 상태 코드는 그대로 돌려준다 */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(e.statusCode)
            .body(mapOf("error" to (e.reason ?: e.statusCode.toString())))

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "서버 오류가 발생했습니다."))
}
