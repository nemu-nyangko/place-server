package org.example.noneplace.domain.canvas.present

import org.example.noneplace.domain.canvas.service.CanvasBroadcaster
import org.example.noneplace.domain.canvas.service.CanvasBroadcaster.Frame
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.BufferOverflowStrategy
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * 캔버스 실시간 변경 스트림.
 * 느린 클라이언트가 [MAX_BUFFERED_FRAMES] 이상 밀리면 연결을 끊는다 →
 * 클라이언트는 재접속 후 스냅샷을 다시 받아 동기화한다(다른 접속자에게 영향 없음).
 */
@Component
class CanvasWebSocketHandler(
    private val broadcaster: CanvasBroadcaster,
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> {
        broadcaster.sessionOpened()

        val updates = Flux.concat(
            Mono.fromCallable { broadcaster.onlineFrame() },
            broadcaster.frames().onBackpressureBuffer(MAX_BUFFERED_FRAMES, {}, BufferOverflowStrategy.ERROR),
        ).map { toMessage(session, it) }

        val pings = Flux.interval(Duration.ofSeconds(PING_INTERVAL_SECONDS))
            .map { session.pingMessage { it.allocateBuffer(0) } }

        val send = session.send(Flux.merge(updates, pings))
        val receive = session.receive().then()

        return Mono.firstWithSignal(send, receive)
            .doFinally { broadcaster.sessionClosed() }
    }

    private fun toMessage(session: WebSocketSession, frame: Frame): WebSocketMessage = when (frame) {
        is Frame.Binary -> session.binaryMessage { it.wrap(frame.bytes) }
        is Frame.Text -> session.textMessage(frame.text)
    }

    companion object {
        private const val PING_INTERVAL_SECONDS = 30L
        private const val MAX_BUFFERED_FRAMES = 200
    }
}
