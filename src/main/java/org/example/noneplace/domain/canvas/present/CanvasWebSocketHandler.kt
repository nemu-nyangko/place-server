package org.example.noneplace.domain.canvas.present

import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class CanvasWebSocketHandler(
    private val listenerContainer: ReactiveRedisMessageListenerContainer
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> {
        val pixelMessages = listenerContainer
            .receive(ChannelTopic.of(CANVAS_CHANNEL))
            .map { session.textMessage(it.message) }

        val pingMessages = Flux.interval(Duration.ofSeconds(PING_INTERVAL_SECONDS))
            .map { session.pingMessage { it.allocateBuffer(0) } }

        val output = session.send(Flux.merge(pixelMessages, pingMessages))
        val input = session.receive().then()
        return output.and(input)
    }

    companion object {
        const val CANVAS_CHANNEL = "canvas-updates"
        private const val PING_INTERVAL_SECONDS = 30L
    }
}
