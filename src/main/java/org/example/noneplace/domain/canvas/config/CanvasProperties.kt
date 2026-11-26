package org.example.noneplace.domain.canvas.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 캔버스 규칙 설정. 등급(게스트/회원)별 픽셀 스톡 파라미터를 분리해 로그인 혜택을 준다.
 */
@ConfigurationProperties(prefix = "canvas")
data class CanvasProperties(
    /** 비로그인(게스트) 픽셀 스톡 */
    val guest: Tier = Tier(maxStock = 10, regenSeconds = 30),
    /** DataGSM 로그인 회원 픽셀 스톡 */
    val member: Tier = Tier(maxStock = 30, regenSeconds = 15),
    /** AI 리터치 쿨타임(초). 회원 전용 기능 */
    val aiRetouchCooldownSeconds: Long = 600,
    /** 한 IP 에서 1분 동안 게스트가 찍을 수 있는 최대 픽셀 수 (게스트 토큰 재발급 어뷰징 방지) */
    val guestPixelsPerIpPerMinute: Long = 1200,
    /** WebSocket 브로드캐스트 묶음 전송 주기(ms) */
    val broadcastIntervalMs: Long = 50,
) {
    data class Tier(val maxStock: Int, val regenSeconds: Long)
}
