package org.example.noneplace.domain.canvas.service

interface AIRetouchService {
    suspend fun retouch(x: Int, y: Int, width: Int, height: Int, currentPixels: ByteArray, prompt: String = ""): ByteArray
}
