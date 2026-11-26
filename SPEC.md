# NonePlace (g/place) API 명세서

## 개요

Reddit r/place를 모방한 학교 협업 픽셀 캔버스 서비스.
512×512 캔버스에 사용자가 픽셀을 찍고, 변경사항이 WebSocket으로 실시간 전파됩니다.
QR로 들어오면 바로 게스트로 참여할 수 있고, DataGSM으로 로그인하면 더 많이 찍을 수 있습니다.

### 기술 스택

| 분류 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3, Spring WebFlux (Kotlin Coroutines) |
| 저장소 | Redis 7 (캔버스 비트맵, 스톡, 작성자, Pub/Sub) |
| 실시간 통신 | WebSocket (바이너리 프레임, 50ms 묶음 전송) |
| 로그인 | DataGSM OAuth 2.0 (Authorization Code + PKCE) |
| 인프라 | Docker Compose + 리버스 프록시 |

---

## 사용자 식별 / 인증

모든 쓰기 요청은 `Authorization: Bearer <token>` 헤더가 필요합니다. 토큰은 서버가 HMAC-SHA256으로 서명한 자체 토큰입니다.

| 등급 | 발급 방법 | userId 형식 | 픽셀 스톡 | AI 리터치 |
|------|-----------|-------------|-----------|-----------|
| 게스트 (`GUEST`) | `POST /api/auth/guest` | `g:<uuid>` | 최대 10개, 30초마다 1개 충전 | 불가 |
| 회원 (`MEMBER`) | DataGSM 로그인 | `m:<DataGSM 계정 id>` | 최대 30개, 15초마다 1개 충전 | 10분 쿨타임 |

- 수치는 `application.yml`의 `canvas.guest`, `canvas.member`, `canvas.ai-retouch-cooldown-seconds`로 조정합니다.
- **관리자**: `ADMIN_IDS`(DataGSM 계정 id, 쉼표 구분)에 있는 회원. 이름은 겹칠 수 있어 id로만 지정합니다. 토큰이 아니라 요청마다 서버 설정으로 판정하므로, 설정을 바꾸고 재시작하면 바로 반영됩니다(재로그인 불필요).
  - 픽셀 무제한: 스톡을 읽지도 줄이지도 않습니다. 편집 모드로 한 번에 최대 1000칸.
  - `/api/auth/me`에 `admin: true`, 스톡 응답에 `unlimited: true`(이때 `stock`/`maxStock`은 한 번에 찍을 수 있는 칸 수).
  - 고정 관리자(ADMIN_IDS): 146. 사이트에서 해제할 수 없습니다.
  - 지정 관리자: 관리자가 사이트의 **관리자 메뉴**(이름 → 관리자 메뉴)에서 지정/해제. Redis Set `admins`에 저장되고 서버가 5초마다 다시 읽으므로 재시작 없이 반영됩니다. 한 번이라도 로그인한 계정만 지정할 수 있습니다(번호 오타 방지).
  - 관리자 API (관리자 토큰 필요, 아니면 403):
    - `GET /api/admin/accounts`: 로그인한 계정 목록(최근 접속 순). 이름, 학년/반/번호, 계정 번호, 마지막 접속, `online`(2분 안에 접속), `admin`, `fixedAdmin`.
    - `PUT /api/admin/admins/{계정번호}`: 관리자 지정 (로그인한 적 없는 번호는 404).
    - `DELETE /api/admin/admins/{계정번호}`: 관리자 해제 (고정 관리자는 409).
  - 접속 기록: 회원이 `/api/auth/me`를 부를 때(클라이언트가 1분마다) Redis `accounts:seen`(ZSET)·`accounts:info`(HASH)에 남깁니다. 30일 지난 기록은 정리합니다.
- 게스트 토큰 남발을 막기 위해 IP(`X-Forwarded-For` 첫 값) 단위 제한이 있습니다.
  - 게스트 토큰 발급: IP당 분당 300회 (`auth.guest-issue-per-ip-per-minute`)
  - 게스트 픽셀: IP당 분당 1200개 (`canvas.guest-pixels-per-ip-per-minute`)

---

## 인증 API

### `POST /api/auth/guest`

게스트 토큰을 발급합니다.

```json
200 { "token": "eyJ...", "userId": "g:6f1c...", "type": "GUEST" }
429 { "error": "Too many guest sessions" }
```

### `GET /api/auth/me`

```json
200 {
  "userId": "m:77", "type": "MEMBER", "name": "홍길동",
  "grade": 1, "classNum": 2, "number": 3,
  "canAiRetouch": true, "loginAvailable": true,
  "memberMaxStock": 30, "memberRegenSeconds": 15,
  "stock": 30, "maxStock": 30, "regenSeconds": 15, "secondsUntilNextCharge": 0
}
401 { "error": "Unauthorized" }
```

`loginAvailable`이 false면 서버에 DataGSM 클라이언트가 설정되지 않은 상태입니다. `memberMaxStock`/`memberRegenSeconds`는 회원 등급 값으로, 게스트에게 보여 주는 로그인 안내 문구에 씁니다.

### `GET /api/auth/datagsm/login?return_to=<클라이언트 URL>`

DataGSM 인가 페이지로 302 리다이렉트합니다. `return_to`의 오리진은 `cors.allowed-origins`에 있어야 하며, 아니면 첫 번째 허용 오리진으로 돌아갑니다(오픈 리다이렉트 방지).

### `GET /api/auth/datagsm/callback`

DataGSM에 등록하는 Redirect URI입니다. 처리 후 클라이언트로 302 리다이렉트합니다.

- 성공: `<return_to>#token=<회원 토큰>`
- 실패: `<return_to>#auth_error=<access_denied | invalid_state | token_exchange_failed | userinfo_failed>`

DataGSM 클라이언트 설정: 스코프 `openid datagsm:account_read datagsm:student_read`, Redirect URI `https://<서버>/api/auth/datagsm/callback`.

---

## 캔버스 API

### `GET /api/canvas`

262,144바이트(512×512) 바이너리. 각 바이트는 색상 인덱스(0~255), `offset = y * 512 + x`.
`Accept-Encoding: gzip`이면 gzip으로 보냅니다(브라우저는 자동 처리).
서버 메모리 미러에서 응답하며 최대 250ms 오래된 스냅샷일 수 있습니다. WebSocket 변경분으로 메웁니다(아래 동기화 순서 참고).

### `GET /api/canvas/palette`

```json
[{ "index": 0, "name": "White", "hex": "#FFFFFF" }, ...]   // 256개
```

- 0~15: 기본 16색 (팔레트 버튼. 클라이언트는 청록(9)을 빼고 그 자리에 컬러피커를 둔다)
- 16~231: 컬러피커 24색상(15° 간격) × 9톤 (HSL, 채도 0.85, 밝기 0.90→0.14)
- 232~255: 회색 24단계
- 서버 `Palette.kt`와 클라이언트 `board.ts`가 같은 공식으로 만든다.

### `GET /api/canvas/cooldown` (인증 필요)

```json
{ "stock": 3, "maxStock": 5, "regenSeconds": 60, "secondsUntilNextCharge": 42, "userId": "g:..." }
```

### `POST /api/canvas/pixel` (인증 필요)

```json
요청 { "x": 100, "y": 200, "color": 3 }
200  { "status": "success", "offset": 102500, "stock": 4, "maxStock": 5, "regenSeconds": 60, "secondsUntilNextCharge": 60 }
400  { "error": "Out of bounds" } | { "error": "Invalid color index" }
401  { "error": "Unauthorized" }
429  { "error": "No stock", "stock": 0, "maxStock": 5, "regenSeconds": 60, "secondsUntilNextCharge": 17 }
429  { "error": "Too many guest pixels from this network", "loginRequired": true }
```

스톡 차감, `SETRANGE`, 작성자 기록, `PUBLISH`가 Lua 스크립트 한 번으로 원자 실행됩니다.

### `POST /api/canvas/pixels` (인증 필요, 편집 모드)

같은 색으로 여러 칸을 한 번에 찍습니다. 칸마다 스톡 1개.

```json
요청 { "pixels": [{ "x": 10, "y": 20 }, { "x": 11, "y": 20 }], "color": 4 }
200  { "status": "success", "placed": 2, "requested": 2, "stock": 3, "maxStock": 5, "regenSeconds": 60, "secondsUntilNextCharge": 60 }
400  { "error": "No pixels" | "Duplicate pixels" | "Out of bounds" | "Invalid color index" } | { "error": "Too many pixels", "max": 5 }
429  { "error": "No stock", ... } | { "error": "Too many guest pixels from this network", "loginRequired": true }
```

- 한 번에 최대 칸 수 = 등급의 최대 스톡(게스트 10, 회원 30).
- 스톡이 모자라면 앞에서부터 남은 스톡만큼만 찍고 `placed`로 알려 줍니다(클라이언트는 나머지를 되돌림).
- 스톡 차감·칸 기록·작성자 기록·PUBLISH(칸들을 이어 붙인 메시지 하나)가 Lua 한 번으로 원자 실행됩니다. `POST /pixel`도 같은 스크립트를 칸 1개로 씁니다.
- 게스트 IP 제한은 칸 수만큼 차감합니다.

### `GET /api/canvas/pixel?x=&y=`

해당 칸을 마지막으로 찍은 사람.

```json
{ "x": 77, "y": 88, "color": 6, "name": "박픽셀", "member": true, "placedAt": 1795656000 }
```

아직 아무도 안 찍었으면 `name`, `member`, `placedAt`이 null입니다. 게스트는 이름이 `"게스트"`입니다.

### `POST /api/canvas/ai-retouch` (회원 전용)

```json
요청 { "x": 0, "y": 0, "width": 16, "height": 16, "prompt": "노을 진 하늘처럼" }
200  { "status": "success", "changed": 12, "message": "웃는 표정으로 바꿨어요", "cooldownSeconds": 600 }
200  { "status": "success", "changed": 0, "message": "...", "cooldownSeconds": 0 }   // 바뀐 칸 없음 → 쿨타임 돌려줌
400  { "error": "Region size must be between 8 and 16" } | { "error": "Region exceeds canvas bounds" } | { "error": "Prompt too long" }
403  { "error": "Login required", "loginRequired": true }
422  { "error": "Refused", "refused": true, "message": "...", "cooldownSeconds": 600 }   // 부적절한 요청, 쿨타임 유지
429  { "error": "Cooldown active", "remainingSeconds": 412 }
502  { "error": "AI retouch failed", "aiFailed": true }   // AI 오류/시간 초과, 쿨타임 돌려줌
503  { "error": "AI retouch unavailable", "aiUnavailable": true }   // OPENAI_API_KEY 없음
```

처리 방식 (`OpenAIAIRetouchService`):
- 입력: ① 영역과 둘레 6칸을 칸당 24px로 키운 PNG(영역은 자홍색 테두리, 바깥은 어둡게) ② 영역 색 번호를 행별로 담은 JSON(`rows[y][x]`)과 쓸 수 있는 색 목록(기본 16색 + 주변에 있는 색)
- 출력: JSON Schema(strict)로 강제한 `{ refused, message, edits: [{x, y, color}] }` — 바꿀 칸만
- 서버가 영역 밖 좌표, 허용되지 않은 색, 원래와 같은 색을 걸러낸 뒤 바뀐 칸만 Lua 한 번으로 적용하고 한 번에 PUBLISH
- 모델: `OPENAI_MODEL`(기본 `gpt-5.4-mini`), `OPENAI_REASONING_EFFORT`(기본 `low`). gpt-4.1-mini / gpt-4.1 / gpt-5.4-mini / gpt-5.4-nano 비교에서 gpt-5.4-mini 품질이 가장 좋았다(응답 3~8초).
- 키가 없으면 리터치는 꺼진다. 가짜 구현(`StubAIRetouchService`, 색 +1)은 `ai.stub-enabled=true`인 테스트에서만 등록된다.
- 쿨타임은 `SET NX EX`로 먼저 선점하고, 실패·변경 없음이면 되돌린다.

### `GET /api/canvas/ai-retouch/cooldown` (인증 필요)

```json
{ "available": true, "remainingSeconds": 0 }   // available = 회원 && AI 설정됨
```

---

## WebSocket `WS /ws-canvas`

클라이언트는 메시지를 보낼 필요가 없습니다. 서버는 30초마다 Ping을 보냅니다.

| 프레임 | 형식 |
|--------|------|
| 바이너리 | `[0x01]` + N × (`x: u16 BE`, `y: u16 BE`, `color: u8`). 50ms 동안 모인 모든 변경(픽셀, AI 리터치 영역)을 한 프레임으로 묶음 |
| 텍스트 | `{"type":"ONLINE","count":123}`: 동시 접속자 수(인스턴스 합산). 접속 직후 1회, 이후 값이 바뀔 때 |

느린 클라이언트가 200프레임 이상 밀리면 서버가 연결을 끊습니다. 클라이언트는 재접속 후 스냅샷을 다시 받습니다.

### 클라이언트 동기화 순서

1. WebSocket을 먼저 연결하고, 받은 바이너리 프레임을 버퍼에 쌓는다.
2. `GET /api/canvas`로 스냅샷을 받아 그린다.
3. 버퍼의 프레임을 순서대로 적용하고, 이후 프레임은 바로 적용한다.

---

## Redis 키

| 키 | 타입 | 내용 |
|----|------|------|
| `canvas` | String | 262,144바이트 캔버스 |
| `canvas-owner` | Hash | field=offset, value=`<epochSec>|<회원 1/0>|<이름>` |
| `pixel-stock:<userId>` | Hash | `stock`, `lastRefillAt` (TTL = 가득 차는 시간) |
| `ai-retouch-cooldown:<userId>` | String | TTL = 남은 쿨타임 |
| `oauth-state:<state>` | String | PKCE verifier + return_to (TTL 10분, 1회용) |
| `rate:<bucket>:<window>` | String | 고정 윈도우 카운터 |
| `online-instances` | Hash | field=instanceId, value=`<접속수>:<epochSec>` |
| `canvas-updates` | Pub/Sub | 5바이트 단위 변경분 (WebSocket 프레임과 같은 형식, 앞의 0x01 제외) |
