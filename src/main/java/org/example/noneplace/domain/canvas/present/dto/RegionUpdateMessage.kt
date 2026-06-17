package org.example.noneplace.domain.canvas.present.dto

data class RegionUpdateMessage(
    val type: String = "REGION_UPDATE",
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixels: List<Int>,
    val userId: String
)
