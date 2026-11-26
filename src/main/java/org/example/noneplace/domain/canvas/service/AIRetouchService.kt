package org.example.noneplace.domain.canvas.service

/** 캔버스 절대 좌표의 칸 하나 변경 */
data class PixelEdit(val x: Int, val y: Int, val color: Int)

/**
 * @param edits 바꿀 칸만 (영역 밖·잘못된 색은 이미 걸러진 상태)
 * @param message 사용자에게 보여줄 한 줄 설명
 * @param refused 부적절한 요청이라 거절했는지
 */
data class RetouchResult(val edits: List<PixelEdit>, val message: String = "", val refused: Boolean = false)

interface AIRetouchService {
    /** (x, y)부터 width×height 영역을 [prompt] 대로 다듬는다. 현재 픽셀은 구현이 미러에서 직접 읽는다. */
    suspend fun retouch(x: Int, y: Int, width: Int, height: Int, prompt: String): RetouchResult
}
