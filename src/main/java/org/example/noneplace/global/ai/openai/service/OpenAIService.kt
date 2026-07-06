package org.example.noneplace.global.ai.openai.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class OpenAIService(
    private val webClient: WebClient
) {

    @Value("\${openai.api-key}")
    private lateinit var apiKey: String

    @Value("\${openai.model:gpt-4o-mini}")
    private lateinit var model: String

    @Value("\${openai.base-url:https://api.openai.com/v1}")
    private lateinit var baseUrl: String

    suspend fun chat(prompt: String): String {
        TODO("OpenAI Chat Completions API 호출 구현 예정")
    }
}
