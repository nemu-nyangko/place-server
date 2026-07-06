package org.example.noneplace.global.ai.gemini.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class GeminiService(
    private val webClient: WebClient
) {

    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    @Value("\${gemini.model:gemini-3.5-flash}")
    private lateinit var model: String

    @Value("\${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private lateinit var baseUrl: String

    suspend fun chat(prompt: String): String {
        TODO("Gemini Interactions API 호출 구현 예정")
    }
}
