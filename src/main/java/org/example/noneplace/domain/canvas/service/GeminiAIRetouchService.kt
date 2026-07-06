package org.example.noneplace.domain.canvas.service

import org.example.noneplace.global.ai.gemini.service.GeminiService
import org.springframework.stereotype.Service

@Service
class GeminiAIRetouchService(
    private val geminiService: GeminiService
) : AIRetouchService {

    override suspend fun retouch(x: Int, y: Int, width: Int, height: Int, currentPixels: ByteArray, prompt: String): ByteArray {
        val pixelList = currentPixels.joinToString(",") { (it.toInt() and 0xFF).toString() }
        val fullPrompt = buildString {
            append(prompt)
            append("\n\nCurrent pixels (${width}x${height} region, color indices 0-15, row-major order):\n")
            append("[$pixelList]\n\n")
            append("Reply with ONLY ${width * height} comma-separated integers (0-15), nothing else.")
        }
        val response = geminiService.chat(fullPrompt)
        return parsePixelResponse(response, currentPixels, width * height)
    }

    private fun parsePixelResponse(response: String, fallback: ByteArray, expected: Int): ByteArray {
        val numbers = Regex("""\b(1[0-5]|[0-9])\b""")
            .findAll(response)
            .map { it.value.toInt() }
            .toList()
        return if (numbers.size == expected) ByteArray(expected) { numbers[it].toByte() }
        else fallback
    }
}
