package org.example.noneplace.domain.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    /** 자체 액세스 토큰 서명용 HMAC 비밀키 (32자 이상) */
    val tokenSecret: String,
    val memberTokenTtlDays: Long = 30,
    val guestTokenTtlDays: Long = 365,
    /** 한 IP 에서 1분 동안 발급 가능한 게스트 토큰 수 */
    val guestIssuePerIpPerMinute: Long = 300,
    /** 관리자 DataGSM 계정 id 목록 (ADMIN_IDS=16,23). 관리자는 픽셀 무제한 */
    val adminIds: List<String> = emptyList(),
    val datagsm: DataGsm,
) {
    data class DataGsm(
        val clientId: String = "",
        val clientSecret: String = "",
        /** DataGSM 클라이언트에 등록한 콜백 URL (이 서버의 /api/auth/datagsm/callback) */
        val redirectUri: String,
        val authorizeUrl: String = "https://oauth.authorization.datagsm.kr/v1/oauth/authorize",
        val tokenUrl: String = "https://oauth.authorization.datagsm.kr/v1/oauth/token",
        val userinfoUrl: String = "https://oauth.resource.datagsm.kr/userinfo",
        val scope: String = "openid datagsm:account_read datagsm:student_read",
    ) {
        val enabled: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    }
}
