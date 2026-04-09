package org.example.noneplace.domain.canvas.present

import jakarta.annotation.PostConstruct
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

@Component
class CanvasWebSocketHandler(
    private val listenerContainer: ReactiveRedisMessageListenerContainer
) : WebSocketHandler {

    private val sink: Sinks.Many<String> = Sinks.many().multicast().directBestEffort()

    @PostConstruct
    fun subscribeToRedis() {
        listenerContainer
            .receive(ChannelTopic.of(CANVAS_CHANNEL))
            .map { it.message }
            .subscribe { message ->
                sink.tryEmitNext(message)
            }
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val output = session.send(
            sink.asFlux().map { session.textMessage(it) }
        )
        val input = session.receive().then()
        return Mono.zip(output, input).then()
    }

    companion object {
        const val CANVAS_CHANNEL = "canvas-updates"
    }
}
