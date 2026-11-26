package org.example.noneplace.domain.canvas.present.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "픽셀 그리기 요청")
data class PixelRequest(
    @Schema(description = "X 좌표 (0~511)", example = "100", minimum = "0", maximum = "511")
    val x: Int,
    @Schema(description = "Y 좌표 (0~511)", example = "200", minimum = "0", maximum = "511")
    val y: Int,
    @Schema(description = "색상 인덱스 (0~255, 0~15 기본색 · 16~255 컬러피커)", example = "3", minimum = "0", maximum = "255")
    val color: Int
)
