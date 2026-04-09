package org.example.noneplace.domain.canvas.config

import org.example.noneplace.domain.canvas.present.CanvasWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig(
    private val canvasWebSocketHandler: CanvasWebSocketHandler
) {

    @Bean
    fun webSocketHandlerMapping(): SimpleUrlHandlerMapping {
        return SimpleUrlHandlerMapping().apply {
            urlMap = mapOf("/ws-canvas" to canvasWebSocketHandler)
            order = -1
        }
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
