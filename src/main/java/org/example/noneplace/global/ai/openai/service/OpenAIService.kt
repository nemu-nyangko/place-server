package org.example.noneplace.global.ai.openai.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/**
 * OpenAI Chat Completions 호출.
 * 그림(PNG) + 텍스트를 함께 보내고, JSON Schema(strict)로 형식이 강제된 JSON 문자열을 돌려받는다.
 */
@Service
class OpenAIService(
    private val webClient: WebClient,
) {
    @Value("\${openai.api-key:}")
    private lateinit var apiKey: String

    @Value("\${openai.model:gpt-5.4-mini}")
    private lateinit var model: String

    /** gpt-5 계열 같은 추론 모델에만 보낸다(minimal/low/medium/high). 비우면 보내지 않는다 */
    @Value("\${openai.reasoning-effort:}")
    private lateinit var reasoningEffort: String

    @Value("\${openai.base-url:https://api.openai.com/v1}")
    private lateinit var baseUrl: String

    @Value("\${openai.timeout-seconds:60}")
    private var timeoutSeconds: Long = 60

    val modelName: String get() = model

    class RefusalException(message: String) : RuntimeException(message)

    suspend fun structured(
        system: String,
        userText: String,
        imagePngBase64: String?,
        schemaName: String,
        schema: Map<String, Any>,
        maxTokens: Int = 4000,
    ): String {
        val content = buildList<Map<String, Any>> {
            add(mapOf("type" to "text", "text" to userText))
            if (imagePngBase64 != null) {
                add(mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,$imagePngBase64", "detail" to "high")))
            }
        }
        val body = buildMap<String, Any> {
            put("model", model)
            put("messages", listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to content)))
            put("response_format", mapOf("type" to "json_schema", "json_schema" to mapOf("name" to schemaName, "strict" to true, "schema" to schema)))
            put("max_completion_tokens", maxTokens)
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
        }
        val response = webClient.post()
            .uri("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono<ChatResponse>()
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .awaitSingle()
        val message = response.choices.firstOrNull()?.message ?: error("empty OpenAI response")
        message.refusal?.let { throw RefusalException(it) }
        return message.content ?: error("empty OpenAI content")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatResponse(val choices: List<Choice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(val message: ResponseMessage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResponseMessage(val content: String? = null, val refusal: String? = null)
}
