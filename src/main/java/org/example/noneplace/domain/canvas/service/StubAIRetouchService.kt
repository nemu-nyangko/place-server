package org.example.noneplace.domain.canvas.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * 테스트 전용 가짜 리터치(색 번호 +1). 실서비스에서 켜지면 캔버스를 망가뜨리므로
 * ai.stub-enabled=true 일 때만 등록된다. 실제 AI 가 없으면 리터치 기능 자체가 꺼진다.
 */
@Service
@ConditionalOnProperty("ai.stub-enabled", havingValue = "true")
class StubAIRetouchService : AIRetouchService {

    override suspend fun retouch(x: Int, y: Int, width: Int, height: Int, currentPixels: ByteArray, prompt: String): ByteArray {
        return ByteArray(currentPixels.size) { i ->
            (((currentPixels[i].toInt() and 0xFF) + 1) % 16).toByte()
        }
    }
}
