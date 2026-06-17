package org.example.noneplace.domain.canvas.config

import org.example.noneplace.domain.canvas.present.CanvasWebSocketHandler
import org.example.noneplace.global.config.CorsConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig(
    private val canvasWebSocketHandler: CanvasWebSocketHandler,
    private val corsConfig: CorsConfig
) {

    @Bean
    fun webSocketHandlerMapping(): SimpleUrlHandlerMapping {
        val mapping = SimpleUrlHandlerMapping()
        mapping.urlMap = mapOf("/ws-canvas" to canvasWebSocketHandler)
        mapping.setCorsConfigurationSource(webSocketCorsSource())
        mapping.order = -1
        return mapping
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()

    private fun webSocketCorsSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = corsConfig.allowedOrigins
            allowedMethods = listOf("GET")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/ws-canvas", config)
        }
    }
}
