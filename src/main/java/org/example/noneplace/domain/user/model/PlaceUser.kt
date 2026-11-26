package org.example.noneplace.domain.user.model

enum class UserType { GUEST, MEMBER }

/**
 * 요청 주체. 게스트는 `g:<uuid>`, DataGSM 회원은 `m:<datagsmAccountId>` 형태의 id 를 가진다.
 * 스톡·쿨타임 Redis 키는 이 id 를 기준으로 한다.
 */
data class PlaceUser(
    val id: String,
    val type: UserType,
    val name: String? = null,
    val grade: Int? = null,
    val classNum: Int? = null,
    val number: Int? = null,
) {
    val isMember: Boolean get() = type == UserType.MEMBER

    /** 캔버스 픽셀 정보에 노출되는 이름 */
    val displayName: String get() = if (isMember) name ?: "회원" else "게스트"
}
