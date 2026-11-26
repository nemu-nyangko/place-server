package org.example.noneplace.domain.canvas.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.example.noneplace.global.redis.config.TestRedisConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/** 가짜 OpenAI 서버로 요청 형식(그림+JSON, JSON Schema)과 응답 검증·필터링을 확인한다 */
@SpringBootTest
@Import(TestRedisConfig::class)
class OpenAIAIRetouchServiceTest {

    @Autowired lateinit var service: AIRetouchService
    @Autowired lateinit var mirror: CanvasMirror

    companion object {
        @Volatile var lastBody: String = ""
        @Volatile var answer: String = ""

        private val fakeOpenAI: DisposableServer = HttpServer.create().port(0)
            .route { r ->
                r.post("/v1/chat/completions") { req, res ->
                    req.receive().aggregate().asString().flatMap { body ->
                        lastBody = body
                        val content = ObjectMapper().writeValueAsString(answer)
                        res.header("Content-Type", "application/json")
                            .sendString(Mono.just("""{"choices":[{"message":{"role":"assistant","content":$content}}]}""")).then()
                    }
                }
            }.bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("openai.api-key") { "test-key" }
            registry.add("openai.base-url") { "http://127.0.0.1:${fakeOpenAI.port()}/v1" }
            registry.add("openai.model") { "test-model" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = fakeOpenAI.disposeNow()
    }

    @Test
    fun `그림과 JSON 을 보내고, 영역 밖·허용 안 된 색·변화 없는 칸은 버린다`() = runTest {
        assertTrue(service is OpenAIAIRetouchService)
        // 영역(100,100) 8x8 은 빈 캔버스(0). 허용 색 = 기본 16색 + 주변에 있는 색
        answer = """{"refused":false,"message":"웃는 얼굴로 바꿨어요","edits":[
            {"x":1,"y":2,"color":3},
            {"x":8,"y":0,"color":3},
            {"x":0,"y":0,"color":200},
            {"x":3,"y":3,"color":0},
            {"x":1,"y":2,"color":4}
        ]}"""
        val result = service.retouch(100, 100, 8, 8, "웃게 해줘")
        assertEquals(listOf(PixelEdit(101, 102, 4)), result.edits)
        assertEquals("웃는 얼굴로 바꿨어요", result.message)

        val req = ObjectMapper().readTree(lastBody)
        assertEquals("test-model", req["model"].asText())
        assertEquals("json_schema", req["response_format"]["type"].asText())
        assertTrue(req["response_format"]["json_schema"]["strict"].asBoolean())
        val parts = req["messages"][1]["content"]
        assertTrue(parts[1]["image_url"]["url"].asText().startsWith("data:image/png;base64,"))
        val userJson = ObjectMapper().readTree(parts[0]["text"].asText())
        assertEquals("웃게 해줘", userJson["instruction"].asText())
        assertEquals(8, userJson["rows"].size())
        assertEquals(8, userJson["rows"][0].size())
    }

    @Test
    fun `거절 응답은 칸을 바꾸지 않는다`() = runTest {
        answer = """{"refused":true,"message":"그 요청은 그릴 수 없어요","edits":[{"x":1,"y":1,"color":3}]}"""
        val result = service.retouch(0, 0, 8, 8, "나쁜 요청")
        assertTrue(result.refused)
        assertTrue(result.edits.isEmpty())
    }
}
