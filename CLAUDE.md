# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Run the app
```bash
# Start Redis first
docker-compose up -d

# Run the Spring Boot server
./gradlew bootRun
```

### Build
```bash
./gradlew build
```

### Run all tests
```bash
./gradlew test
```

### Run a single test class
```bash
./gradlew test --tests "org.example.noneplace.domain.canvas.service.CanvasServiceTest"
```

Tests spin up an embedded Redis on a random free port (via `TestRedisConfig`) — no external Redis needed for tests.

## Architecture

NonePlace is a Reddit r/place–style collaborative pixel canvas. The stack is **Spring Boot 3 + WebFlux (Kotlin Coroutines)** with **Redis 7** as the sole datastore.

### Data flow

1. Client calls `POST /api/canvas/pixel` → `CanvasController` resolves the user ID, checks cooldown, writes the pixel to Redis (`SETRANGE` on key `canvas`), then publishes a JSON message to the Redis Pub/Sub channel `canvas-updates`.
2. `CanvasWebSocketHandler` subscribes to `canvas-updates` via `ReactiveRedisMessageListenerContainer` and fans out every message as a WebSocket text frame to all connected clients. It also sends a WebSocket Ping every 30 s.
3. The canvas is stored as a flat 262,144-byte string in Redis (key `canvas`). Each byte is a color index 0–15. Offset formula: `y * 512 + x`. Redis AOF persistence keeps it durable across restarts; `initCanvas()` only writes the blank canvas if the key doesn't already exist.

### Key components

| Layer | File | Responsibility |
|-------|------|----------------|
| Controller | `CanvasController` | REST endpoints (`/api/canvas`), user ID resolution, validation, cooldown check |
| WebSocket | `CanvasWebSocketHandler` | Reactive WS handler — subscribes to Redis channel, streams to clients |
| Service | `CanvasService` | Redis bitmap read/write, Pub/Sub publish, region operations |
| Service | `CooldownService` | Per-user TTL keys in Redis (`cooldown:<userId>`); configurable via `canvas.cooldown-seconds` and `canvas.ai-retouch-cooldown-seconds` |
| Service | `AIRetouchService` (interface) / `StubAIRetouchService` | Region retouch stub — cycles each pixel color +1 mod 16. Replace stub with real AI API implementation |
| Service | `UserService` | Resolves `X-User-Id` header to a UUID; issues a new UUID if header is absent or invalid |
| Config | `RedisConfig` | Registers `ReactiveRedisTemplate<String, ByteArray>` and `ReactiveRedisMessageListenerContainer` |
| Config | `CorsConfig` | CORS via `application.yml` `cors.allowed-origins`; exposes `X-User-Id` header |
| Config | `WebSocketConfig` | Maps `/ws-canvas` to the handler; applies CORS to the WS endpoint |
| Config | `Palette` | Immutable 16-color palette; `isValid()` guards color index writes |

### User identity

No auth or login. The `X-User-Id` header carries a UUID. `UserService.resolveUserId` issues a fresh UUID if the header is missing or malformed. Cooldowns are keyed on this UUID.

### AI Retouch endpoint

`POST /api/canvas/ai-retouch` accepts a region (8×16 pixels max), reads the current pixels, calls `AIRetouchService.retouch`, writes the result back, and sets a 15-minute cooldown. `StubAIRetouchService` is the current implementation — swap it out by providing a real `@Service` that implements the `AIRetouchService` interface.

### Configuration

Cooldown durations in `application.yml`:
- `canvas.cooldown-seconds` (default 300) — pixel draw cooldown
- `canvas.ai-retouch-cooldown-seconds` (default 900) — AI retouch cooldown
- `cors.allowed-origins` — list of allowed origins

### Testing

Integration tests use `it.ozimov:embedded-redis` via `TestRedisConfig`, which starts an in-process Redis on a random port and registers it as the primary `ReactiveRedisConnectionFactory`. Annotate test classes with `@Import(TestRedisConfig::class)`.
