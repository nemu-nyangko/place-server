package org.example.noneplace.global.ai.gemini.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

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
        val response = webClient.post()
            .uri("$baseUrl/interactions")
            .header("x-goog-api-key", apiKey)
            .bodyValue(InteractionRequest(model = model, input = prompt))
            .retrieve()
            .awaitBody<InteractionResponse>()

        return response.steps
            .flatMap { it.content }
            .filter { it.type == "text" }
            .joinToString("") { it.text }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InteractionRequest(
        val model: String,
        val input: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InteractionResponse(
        val steps: List<Step> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Step(
        val type: String = "",
        val content: List<Content> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Content(
        val type: String = "",
        val text: String = ""
    )
}
