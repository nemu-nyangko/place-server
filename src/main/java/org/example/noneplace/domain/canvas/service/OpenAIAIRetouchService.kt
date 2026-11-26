package org.example.noneplace.domain.canvas.service

import org.example.noneplace.global.ai.openai.service.OpenAIService
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

/** OPENAI_API_KEY 가 있을 때만 활성화. 없으면 [StubAIRetouchService] 가 쓰인다. */
@Primary
@Service
@ConditionalOnExpression("'\${openai.api-key:}' != ''")
class OpenAIAIRetouchService(
    private val openAIService: OpenAIService
) : AIRetouchService {

    override suspend fun retouch(x: Int, y: Int, width: Int, height: Int, currentPixels: ByteArray, prompt: String): ByteArray {
        val pixelList = currentPixels.joinToString(",") { (it.toInt() and 0xFF).toString() }
        val fullPrompt = buildString {
            append(prompt)
            append("\n\nCurrent pixels (${width}x${height} region, color indices 0-255, row-major order; 0-15 are the base palette, 16-255 are custom shades):\n")
            append("[$pixelList]\n\n")
            append("Reply with ONLY ${width * height} comma-separated integers (0-255), nothing else. Prefer the base colors 0-15 for new pixels.")
        }
        val response = openAIService.chat(fullPrompt)
        return parsePixelResponse(response, currentPixels, width * height)
    }

    private fun parsePixelResponse(response: String, fallback: ByteArray, expected: Int): ByteArray {
        val numbers = Regex("""\b(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\b""")
            .findAll(response)
            .map { it.value.toInt() }
            .toList()
        return if (numbers.size == expected) ByteArray(expected) { numbers[it].toByte() }
        else fallback
    }
}
