package org.example.noneplace.domain.canvas.present.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "AI 리터치 요청")
data class RetouchRequest(
    @Schema(description = "리터치 영역 좌상단 X 좌표 (0~511)", example = "0", minimum = "0", maximum = "511")
    val x: Int,
    @Schema(description = "리터치 영역 좌상단 Y 좌표 (0~511)", example = "0", minimum = "0", maximum = "511")
    val y: Int,
    @Schema(description = "영역 너비 (8~16)", example = "16", minimum = "8", maximum = "16", defaultValue = "16")
    val width: Int = 16,
    @Schema(description = "영역 높이 (8~16)", example = "16", minimum = "8", maximum = "16", defaultValue = "16")
    val height: Int = 16,
    @Schema(description = "AI에게 전달할 프롬프트 (리터치 지시문)", example = "Make this region look like a sunset sky")
    val prompt: String = ""
)
