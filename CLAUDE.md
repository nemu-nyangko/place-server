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

NonePlace (g/place) is a Reddit r/place–style collaborative pixel canvas for GSM students. The stack is **Spring Boot 3 + WebFlux (Kotlin Coroutines)** with **Redis 7** as the sole datastore. Full API: `SPEC.md`.

### Data flow

1. Client gets a token (`POST /api/auth/guest`, or DataGSM OAuth login) and sends `Authorization: Bearer <token>`.
2. `POST /api/canvas/pixel` → `CanvasService.placePixel` runs one Lua script: token-bucket stock consume → `SETRANGE canvas` → `HSET canvas-owner` → `PUBLISH canvas-updates` (5-byte packed `x:u16,y:u16,color:u8`). One Redis round trip, atomic.
3. `CanvasBroadcaster` holds **one** Redis subscription per instance, applies updates to `CanvasMirror` (in-memory canvas), and every 50 ms emits one binary WS frame (`0x01` + packed pixels) to all sessions via a shared sink. It also publishes the online count (`{"type":"ONLINE"}` text frames).
4. `GET /api/canvas` is served from `CanvasMirror` (gzip cached ~250 ms), not Redis.

### Key components

| Layer | File | Responsibility |
|-------|------|----------------|
| Controller | `CanvasController` | Canvas REST endpoints, tier checks, guest per-IP limit |
| Controller | `AuthController` | Guest token, `/me`, DataGSM login/callback |
| WebSocket | `CanvasWebSocketHandler` | Streams broadcaster frames; drops clients that fall 200 frames behind |
| Service | `CanvasService` | Lua place/region scripts, pixel owner lookup |
| Service | `CanvasMirror` / `CanvasBroadcaster` | In-memory canvas + single subscription fan-out |
| Service | `PixelStockService` | Tier (guest/member) stock read; keys `pixel-stock:<userId>` |
| Service | `CooldownService` | AI retouch cooldown (`SET NX EX` claim/release) |
| Service | `AuthTokenService` | HMAC-signed stateless tokens (`g:<uuid>` guest, `m:<id>` member) |
| Service | `DataGsmOAuthService` | Authorization Code + PKCE against DataGSM, state in Redis |
| Service | `RateLimitService` | Fixed-window counters |
| Config | `CanvasProperties` / `AuthProperties` | `canvas.*`, `auth.*` in `application.yml` |

### Tiers

Guest: 10 pixels, +1 per 30 s, no AI retouch. Member (DataGSM): 30 pixels, +1 per 15 s, AI retouch every 10 min. Tune in `application.yml`.

### Deployment

`docker-compose.prod.yml` (+ `docker-compose.proxy.yml` to join the reverse proxy's network). Secrets live in `.env` (see `.env.example`). The app port is bound to 127.0.0.1 only. Production path: gsmsv HTTPS proxy (`gplace.https.gsmsv.site`) → Nginx Proxy Manager (port 80) → app. gsmsv overwrites `X-Forwarded-For` with the real client IP, so `ClientIp` uses its first entry (`X-Real-IP` is client-spoofable there). `prod` profile disables Swagger.

### Testing

Integration tests use `it.ozimov:embedded-redis` via `TestRedisConfig`. `AuthControllerTest` runs the full OAuth flow against an in-test fake DataGSM server.
