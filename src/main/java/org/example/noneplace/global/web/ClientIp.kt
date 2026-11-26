package org.example.noneplace.global.web

import org.springframework.http.server.reactive.ServerHttpRequest

/**
 * 게스트 레이트 리밋에 쓰는 클라이언트 IP.
 *
 * 배포 경로: 사용자 → gsmsv HTTPS 프록시 → Nginx Proxy Manager → 앱.
 * gsmsv 프록시는 X-Forwarded-For 를 실제 접속 IP 하나로 덮어쓰고(클라이언트가 보낸 값은 버림),
 * NPM 이 그 뒤에 자기 앞 주소를 덧붙인다 → 첫 번째 값이 실제 클라이언트 IP.
 * 반면 X-Real-IP 는 클라이언트가 보낸 값이 그대로 통과하므로 신뢰하지 않는다.
 * 앱 포트는 외부에 직접 노출하지 않는다는 전제(127.0.0.1 바인딩).
 */
object ClientIp {
    fun of(request: ServerHttpRequest): String =
        request.headers.getFirst("X-Forwarded-For")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"
}
