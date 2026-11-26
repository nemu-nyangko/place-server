package org.example.noneplace.domain.canvas.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.global.ai.openai.service.OpenAIService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * OpenAI 로 AI 리터치. OPENAI_API_KEY 가 있을 때만 활성화되고, 없으면 리터치는 꺼진다(503).
 *
 * 입력: ① 영역과 주변을 크게 키운 그림(서버가 직접 그림) ② 영역의 색 번호를 행별로 담은 JSON.
 * 출력: 바꿀 칸만 담은 JSON. 좌표·색을 서버가 다시 검증한 뒤 적용한다.
 */
@Service
@ConditionalOnExpression("'\${openai.api-key:}' != ''")
class OpenAIAIRetouchService(
    private val openAIService: OpenAIService,
    private val mirror: CanvasMirror,
    private val objectMapper: ObjectMapper,
) : AIRetouchService {

    private val log = LoggerFactory.getLogger(javaClass)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AiEdit(val x: Int = -1, val y: Int = -1, val color: Int = -1)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AiAnswer(val refused: Boolean = false, val message: String = "", val edits: List<AiEdit> = emptyList())

    override suspend fun retouch(x: Int, y: Int, width: Int, height: Int, prompt: String): RetouchResult {
        // 주변 맥락: 영역 둘레 MARGIN 칸 (캔버스 밖은 잘라냄)
        val cx = maxOf(0, x - MARGIN)
        val cy = maxOf(0, y - MARGIN)
        val cw = minOf(CANVAS, x + width + MARGIN) - cx
        val ch = minOf(CANVAS, y + height + MARGIN) - cy
        val context = mirror.region(cx, cy, cw, ch)
        fun at(px: Int, py: Int) = context[(py - cy) * cw + (px - cx)].toInt() and 0xFF

        val rows = List(height) { r -> List(width) { c -> at(x + c, y + r) } }
        val present = (context.map { it.toInt() and 0xFF }).toSortedSet()
        val allowed = (0 until 16).toSortedSet().apply { addAll(present) }

        val userJson = objectMapper.writeValueAsString(
            mapOf(
                "instruction" to prompt.ifBlank { "Tidy up and improve this pixel art naturally." },
                "width" to width,
                "height" to height,
                "rows" to rows,
                "allowedColors" to allowed.associate { it.toString() to colorLabel(it) },
                "image" to "Enlarged view. Target region is outlined in magenta (${width}x$height cells, thin grid lines). " +
                    "Outside the outline is surrounding canvas for context only (dimmed), you cannot edit it.",
            )
        )

        val started = System.currentTimeMillis()
        val raw = try {
            openAIService.structured(SYSTEM_PROMPT, userJson, renderPng(context, cx, cy, cw, ch, x, y, width, height), "pixel_edits", SCHEMA)
        } catch (e: OpenAIService.RefusalException) {
            return RetouchResult(emptyList(), "이 요청은 그릴 수 없어요", refused = true)
        }
        val answer = objectMapper.readValue<AiAnswer>(raw)

        val edits = answer.edits
            .filter { it.x in 0 until width && it.y in 0 until height && it.color in allowed }
            .associateBy { it.y * width + it.x } // 같은 칸이 여러 번 오면 마지막 것
            .values
            .filter { rows[it.y][it.x] != it.color }
            .map { PixelEdit(x + it.x, y + it.y, it.color) }
        log.info(
            "AI retouch model={} {}x{} at ({},{}) took={}ms edits={}/{} refused={}",
            openAIService.modelName, width, height, x, y, System.currentTimeMillis() - started,
            edits.size, answer.edits.size, answer.refused,
        )
        if (answer.refused) return RetouchResult(emptyList(), answer.message.take(200), refused = true)
        return RetouchResult(edits, answer.message.take(200))
    }

    private fun colorLabel(index: Int): String {
        val c = Palette.COLORS[index]
        return if (index < 16) "${c.hex} ${c.name}" else c.hex
    }

    /** 맥락 포함 영역을 칸당 CELL px 로 키워 그린다. 글꼴이 필요 없도록 글자는 넣지 않는다. */
    private fun renderPng(context: ByteArray, cx: Int, cy: Int, cw: Int, ch: Int, x: Int, y: Int, w: Int, h: Int): String {
        val img = BufferedImage(cw * CELL, ch * CELL, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val rgb = Palette.COLORS.map { Color(Integer.parseInt(it.hex.substring(1), 16)) }
        for (r in 0 until ch) for (c in 0 until cw) {
            val inside = cx + c in x until x + w && cy + r in y until y + h
            val base = rgb[context[r * cw + c].toInt() and 0xFF]
            g.color = if (inside) base else Color(base.red * 6 / 10, base.green * 6 / 10, base.blue * 6 / 10)
            g.fillRect(c * CELL, r * CELL, CELL, CELL)
        }
        // 영역 안 격자선
        g.color = Color(128, 128, 128, 90)
        val ox = (x - cx) * CELL
        val oy = (y - cy) * CELL
        for (i in 1 until w) g.drawLine(ox + i * CELL, oy, ox + i * CELL, oy + h * CELL - 1)
        for (i in 1 until h) g.drawLine(ox, oy + i * CELL, ox + w * CELL - 1, oy + i * CELL)
        // 영역 테두리 (자홍색)
        g.color = Color(255, 0, 255)
        for (t in 1..3) g.drawRect(ox - t, oy - t, w * CELL + 2 * t - 1, h * CELL + 2 * t - 1)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    companion object {
        private const val CANVAS = 512
        private const val MARGIN = 6
        private const val CELL = 24

        private val SYSTEM_PROMPT = """
            You are a pixel artist editing a small region of a shared pixel canvas used by students at a Korean high school.
            The user selected a target region and wrote an instruction (usually in Korean).

            Input:
            - An image: the canvas around the target, enlarged so each pixel is a square. The target region is outlined in magenta.
              Pixels outside the outline are context only (drawn dimmed) and cannot be edited.
            - JSON: exact color index of every target pixel as rows[y][x] (x to the right, y downward, both 0-based inside the region),
              and allowedColors (index -> hex and name). Use the image to understand shapes, the JSON for exact positions and colors.

            Output: only the pixels you change, as edits with region-local x, y and a color from allowedColors.
            - Follow the instruction. Keep existing drawings unless the instruction asks to change them; do not repaint the background needlessly.
            - Make clean, deliberate pixel art: clear shapes, consistent lines 1 pixel wide, readable at small size, matching the surrounding style.
            - Pixels you do not list stay as they are.
            - If the instruction asks for hateful, sexual, violent, bullying, discriminatory content, personal information, or offensive words/symbols,
              set refused=true with no edits.
            - message: one short, friendly Korean sentence telling the user what you did (or why you refused).
        """.trimIndent()

        private val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("refused", "message", "edits"),
            "properties" to mapOf(
                "refused" to mapOf("type" to "boolean"),
                "message" to mapOf("type" to "string"),
                "edits" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("x", "y", "color"),
                        "properties" to mapOf(
                            "x" to mapOf("type" to "integer"),
                            "y" to mapOf("type" to "integer"),
                            "color" to mapOf("type" to "integer"),
                        ),
                    ),
                ),
            ),
        )
    }
}
