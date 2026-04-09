package org.example.noneplace.domain.canvas.config

object Palette {
    data class Color(val index: Byte, val name: String, val hex: String)

    val COLORS = arrayOf(
        Color(0,  "White",       "#FFFFFF"),
        Color(1,  "Light Gray",  "#D4D7D9"),
        Color(2,  "Dark Gray",   "#898D90"),
        Color(3,  "Black",       "#000000"),
        Color(4,  "Red",         "#FF4500"),
        Color(5,  "Orange",      "#FFA800"),
        Color(6,  "Yellow",      "#FFD635"),
        Color(7,  "Light Green", "#00A368"),
        Color(8,  "Green",       "#00CC78"),
        Color(9,  "Teal",        "#009EAA"),
        Color(10, "Light Blue",  "#3690EA"),
        Color(11, "Blue",        "#0052CC"),
        Color(12, "Indigo",      "#493AC1"),
        Color(13, "Purple",      "#811E9F"),
        Color(14, "Pink",        "#FF99AA"),
        Color(15, "Brown",       "#6D482F"),
    )

    fun isValid(colorIdx: Byte): Boolean = colorIdx >= 0 && colorIdx < COLORS.size
}
