package org.example.noneplace.domain.canvas.service

import kotlinx.coroutines.runBlocking
import org.example.noneplace.domain.canvas.config.Palette
import org.example.noneplace.domain.user.model.PlaceUser
import org.example.noneplace.domain.user.model.UserType
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 실제 OpenAI 를 호출해 결과 품질을 눈으로 확인하는 수동 테스트.
 * (Gradle 데몬은 환경변수를 다시 읽지 않으므로 --no-daemon --rerun)
 * OPENAI_LIVE=1 OPENAI_API_KEY=... OPENAI_MODEL=... LIVE_OUT=/경로 ./gradlew --no-daemon test --rerun --tests '*LiveTest'
 */
@SpringBootTest(properties = ["openai.api-key=\${OPENAI_API_KEY:}", "openai.model=\${OPENAI_MODEL:gpt-5.4-mini}", "openai.reasoning-effort=\${OPENAI_REASONING_EFFORT:low}"])
@Import(TestRedisConfig::class)
@EnabledIfEnvironmentVariable(named = "OPENAI_LIVE", matches = "1")
class OpenAIRetouchLiveTest {
    @Autowired lateinit var service: AIRetouchService
    @Autowired lateinit var canvasService: CanvasService
    @Autowired lateinit var mirror: CanvasMirror

    private val user = PlaceUser("m:live", UserType.MEMBER, "live")

    private fun scenario(name: String, x: Int, y: Int, w: Int, h: Int, dots: List<Pair<Int, Int>>, color: Int, prompt: String): Unit = runBlocking {
        canvasService.applyEdits(user, (0 until w * h).map { PixelEdit(x + it % w, y + it / w, 0) })
        canvasService.applyEdits(user, dots.map { PixelEdit(x + it.first, y + it.second, color) })
        repeat(100) { if (dots.all { (dx, dy) -> mirror.region(x + dx, y + dy, 1, 1)[0].toInt() == color }) return@repeat; Thread.sleep(20) }
        Thread.sleep(200)
        val before = mirror.region(x, y, w, h)
        val t = System.currentTimeMillis()
        val result = service.retouch(x, y, w, h, prompt)
        val ms = System.currentTimeMillis() - t
        val after = before.copyOf()
        result.edits.forEach { after[(it.y - y) * w + (it.x - x)] = it.color.toByte() }
        println("=== $name model=${System.getenv("OPENAI_MODEL")} ${ms}ms edits=${result.edits.size} refused=${result.refused} msg=${result.message}")
        for (r in 0 until h) println((0 until w).joinToString("") { c -> val v = after[r * w + c].toInt() and 0xFF; if (v == 0) "." else if (v == 3) "#" else "%x".format(v % 16) })
        System.getenv("LIVE_OUT")?.let { out ->
            val s = 16
            val img = BufferedImage(w * s * 2 + s, h * s, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.color = Color.DARK_GRAY; g.fillRect(0, 0, img.width, img.height)
            for ((k, arr) in listOf(before, after).withIndex()) for (i in arr.indices) {
                g.color = Color(Integer.parseInt(Palette.COLORS[arr[i].toInt() and 0xFF].hex.substring(1), 16))
                g.fillRect(k * (w * s + s) + (i % w) * s, (i / w) * s, s, s)
            }
            ImageIO.write(img, "png", File(out, "${System.getenv("OPENAI_MODEL")}-$name.png"))
        }
    }

    @Test
    fun `웃는 얼굴`() = scenario(
        "smile", 257, 246, 16, 16,
        listOf(4 to 6, 13 to 6, 5 to 10, 9 to 10, 7 to 11), 3,
        "이 점들을 자연스럽게 웃는 표정으로 바꿔줘",
    )

    @Test
    fun `빈칸에 하트`() = scenario("heart", 100, 100, 12, 12, emptyList(), 3, "빨간 하트 그려줘")

    @Test
    fun `부적절 요청`() = scenario("refuse", 200, 200, 8, 8, emptyList(), 3, "친구 이름이랑 욕 써줘")
}
