package org.example.noneplace.domain.canvas.config

/**
 * 256색 팔레트. 픽셀 1개 = 색상 인덱스 1바이트라 저장/전송 형식은 그대로다.
 * - 0~15  : 기본 16색 (팔레트 버튼)
 * - 16~231: 컬러피커 9톤 × 24색상 (밝은 톤 → 어두운 톤, 색상환 15° 간격)
 * - 232~255: 컬러피커 회색 24단계
 * 클라이언트(board.ts)도 같은 공식으로 생성하므로 공식을 바꾸면 양쪽을 함께 바꿔야 한다.
 */
object Palette {
    data class Color(val index: Int, val name: String, val hex: String)

    const val SIZE = 256
    const val HUES = 24
    val TONE_LIGHTNESS = doubleArrayOf(0.90, 0.80, 0.70, 0.60, 0.50, 0.40, 0.30, 0.22, 0.14)
    const val TONE_SATURATION = 0.85

    private val BASE = listOf(
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

    val COLORS: List<Color> = buildList {
        addAll(BASE)
        TONE_LIGHTNESS.forEachIndexed { tone, l ->
            for (h in 0 until HUES) add(Color(size, "Hue ${h * 15} Tone $tone", hsl(h * 15.0, TONE_SATURATION, l)))
        }
        for (i in 0 until 24) {
            val v = Math.round(255.0 * (24 - i) / 25).toInt()
            add(Color(size, "Gray $i", hex(v, v, v)))
        }
    }

    init {
        check(COLORS.size == SIZE)
    }

    fun isValid(colorIdx: Int): Boolean = colorIdx in 0 until SIZE

    private fun hsl(h: Double, s: Double, l: Double): String {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val x = c * (1 - Math.abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return hex(Math.round((r + m) * 255).toInt(), Math.round((g + m) * 255).toInt(), Math.round((b + m) * 255).toInt())
    }

    private fun hex(r: Int, g: Int, b: Int) = "#%02X%02X%02X".format(r, g, b)
}
