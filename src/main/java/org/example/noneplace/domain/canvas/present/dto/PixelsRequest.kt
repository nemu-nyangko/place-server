package org.example.noneplace.domain.canvas.present.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "여러 칸 한 번에 찍기 요청 (편집 모드)")
data class PixelsRequest(
    @Schema(description = "찍을 칸 목록. 최대 개수는 등급의 최대 스톡(게스트 5, 회원 30)")
    val pixels: List<Cell> = emptyList(),
    @Schema(description = "색상 인덱스 (0~255)", example = "3")
    val color: Int,
) {
    data class Cell(val x: Int, val y: Int)
}
