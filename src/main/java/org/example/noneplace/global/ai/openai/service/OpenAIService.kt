package org.example.noneplace.global.ai.openai.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

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
        val response = webClient.post()
            .uri("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(ChatRequest(model = model, messages = listOf(Message(role = "user", content = prompt))))
            .retrieve()
            .awaitBody<ChatResponse>()

        return response.choices.firstOrNull()?.message?.content ?: ""
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatRequest(
        val model: String,
        val messages: List<Message>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Message(
        val role: String,
        val content: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatResponse(
        val choices: List<Choice> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(
        val message: Message = Message("", "")
    )
}
