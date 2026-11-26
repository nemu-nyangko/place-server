package org.example.noneplace.domain.canvas.service

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.example.noneplace.domain.canvas.config.CanvasProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import org.springframework.stereotype.Component
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.retry.Retry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 인스턴스당 하나의 Redis 구독으로 모든 WebSocket 세션에 변경분을 전파한다.
 *
 * - 세션마다 Redis 를 구독하지 않으므로 접속자 수가 늘어도 Redis 부하가 일정하다.
 * - 변경분을 [CanvasProperties.broadcastIntervalMs] 단위로 묶어 바이너리 프레임 하나로 보낸다.
 *   프레임 형식: [0x01] + N × (x:u16, y:u16, color:u8)
 * - 동시 접속자 수는 인스턴스별 카운트를 Redis Hash 로 합산해 5초마다 텍스트 프레임으로 보낸다.
 */
@Component
class CanvasBroadcaster(
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val stringRedisTemplate: ReactiveStringRedisTemplate,
    private val canvasService: CanvasService,
    private val mirror: CanvasMirror,
    private val properties: CanvasProperties,
) {
    sealed interface Frame {
        class Binary(val bytes: ByteArray) : Frame
        class Text(val text: String) : Frame
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val instanceId = UUID.randomUUID().toString()
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val sink = Sinks.many().multicast().directBestEffort<Frame>()
    private val localSessions = AtomicInteger()
    private val onlineCount = AtomicInteger()
    private val lastSentOnline = AtomicInteger(-1)
    private val disposables = Disposables.composite()

    fun frames(): Flux<Frame> = sink.asFlux()

    fun onlineFrame(): Frame = Frame.Text("""{"type":"ONLINE","count":${maxOf(onlineCount.get(), 1)}}""")

    fun sessionOpened() {
        localSessions.incrementAndGet()
    }

    fun sessionClosed() {
        localSessions.decrementAndGet()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // 1) 구독이 실제로 활성화된 뒤 2) 스냅샷을 적재해야 그 사이 변경분이 빠지지 않는다
        disposables.add(
            listenerContainer
                .receiveLater(listOf(ChannelTopic.of(CanvasService.CANVAS_CHANNEL)), RAW, RAW)
                .flatMapMany { messages: Flux<ReactiveSubscription.Message<ByteBuffer, ByteBuffer>> ->
                    mono { mirror.load(canvasService.loadCanvasFromRedis()) }
                        .doOnSuccess { log.info("Canvas mirror loaded") }
                        .thenMany(messages)
                }
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
                .subscribe({ message ->
                    val bytes = ByteArray(message.message.remaining()).also { message.message.get(it) }
                    mirror.apply(bytes)
                    queue.add(bytes)
                }, { e -> log.error("Canvas subscription terminated", e) })
        )

        // 단일 스레드 틱에서만 sink 로 발행 → 직렬화 보장
        disposables.add(
            Flux.interval(Duration.ofMillis(properties.broadcastIntervalMs))
                .subscribe { flush() }
        )

        disposables.add(
            Flux.interval(Duration.ZERO, Duration.ofSeconds(ONLINE_REPORT_SECONDS))
                .concatMap { mono { refreshOnlineCount() }.onErrorResume { Mono.empty() } }
                .subscribe()
        )
    }

    private fun flush() {
        if (!queue.isEmpty()) {
            val out = ByteArrayOutputStream(1 + CanvasMirror.PIXEL_BYTES * 64)
            out.write(FRAME_PIXELS.toInt())
            while (true) {
                val next = queue.poll() ?: break
                out.write(next)
            }
            sink.tryEmitNext(Frame.Binary(out.toByteArray()))
        }
        val online = onlineCount.get()
        if (online != lastSentOnline.get()) {
            lastSentOnline.set(online)
            sink.tryEmitNext(onlineFrame())
        }
    }

    private suspend fun refreshOnlineCount() {
        val now = Instant.now().epochSecond
        val hash = stringRedisTemplate.opsForHash<String, String>()
        hash.put(ONLINE_KEY, instanceId, "${localSessions.get()}:$now").awaitSingleOrNull()
        val entries = hash.entries(ONLINE_KEY).collectList().awaitSingleOrNull() ?: emptyList()
        var total = 0
        for (entry in entries) {
            val parts = entry.value.split(':')
            val count = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val at = parts.getOrNull(1)?.toLongOrNull() ?: 0
            if (now - at <= ONLINE_REPORT_SECONDS * 3) {
                total += count
            } else {
                hash.remove(ONLINE_KEY, entry.key).awaitSingleOrNull()
            }
        }
        onlineCount.set(total)
    }

    @PreDestroy
    fun stop() {
        disposables.dispose()
        stringRedisTemplate.opsForHash<String, String>().remove(ONLINE_KEY, instanceId).subscribe({}, {})
    }

    companion object {
        const val FRAME_PIXELS: Byte = 0x01
        private val RAW: SerializationPair<ByteBuffer> = SerializationPair.raw()
        private const val ONLINE_KEY = "online-instances"
        private const val ONLINE_REPORT_SECONDS = 5L
    }
}
