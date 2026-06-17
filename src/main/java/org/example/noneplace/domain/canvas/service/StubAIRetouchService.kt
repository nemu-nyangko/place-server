package org.example.noneplace.domain.canvas.service

class StubAIRetouchService : AIRetouchService {

    override suspend fun retouch(x: Int, y: Int, width: Int, height: Int, currentPixels: ByteArray, prompt: String): ByteArray {
        return ByteArray(currentPixels.size) { i ->
            (((currentPixels[i].toInt() and 0xFF) + 1) % 16).toByte()
        }
    }
}
