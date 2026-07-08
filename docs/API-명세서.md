# NonePlace API 명세서

> 이 문서는 **확장 가능한 형태**로 작성되었습니다. 새 엔드포인트를 추가할 때는 문서 하단의 [부록 A. 엔드포인트 추가 템플릿](#부록-a-엔드포인트-추가-템플릿)을 복사해 채워 넣으세요.

---

## 0. 문서 규칙

- **Base URL**: `http://{host}:{port}` (기본 `http://localhost:8080`)
- **API Prefix**: `/api/canvas`
- **문서 자동화**: 실제 스펙은 SpringDoc으로 자동 생성됩니다.
  - Swagger UI: `GET /swagger-ui.html`
  - OpenAPI JSON: `GET /v3/api-docs`
- **Content-Type**: 별도 명시가 없으면 요청/응답 모두 `application/json`.
- 각 엔드포인트는 아래 표준 항목으로 기술합니다: `메서드·경로 / 설명 / 요청 헤더 / 요청 파라미터·바디 / 응답 / 상태 코드 / 예시`.

## 1. 공통 규격

### 1.1 인증 / 사용자 식별

별도의 로그인·토큰 인증이 없습니다. 모든 요청은 아래 헤더로 사용자를 식별합니다.

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID. 없거나 형식이 올바르지 않으면 서버가 신규 UUID를 발급해 **응답 헤더** `X-User-Id`로 반환합니다. |

> **클라이언트 유의사항**: 최초 응답의 `X-User-Id`를 저장해 이후 모든 요청에 재사용해야 픽셀 스톡·AI 리터치 쿨타임이 동일 사용자 기준으로 적용됩니다.

### 1.2 공통 응답 헤더

| 헤더 | 설명 |
|------|------|
| `X-User-Id` | 요청을 처리한(또는 새로 발급한) 사용자 UUID. |

### 1.3 공통 오류 응답

전역 예외 처리기(`GlobalExceptionHandler`)가 아래 형태로 오류를 반환합니다.

```json
{ "error": "오류 메시지" }
```

| 상태 코드 | 발생 상황 | 메시지 예시 |
|-----------|-----------|-------------|
| `400 Bad Request` | 잘못된 인자 / 요청 형식 오류 | `"잘못된 요청입니다."`, `"요청 형식이 올바르지 않습니다."` |
| `429 Too Many Requests` | 픽셀 스톡 소진 / AI 리터치 쿨타임 중 | `"No stock"` (+ `secondsUntilNextCharge`) / `"Cooldown active"` (+ `remainingSeconds`) |
| `500 Internal Server Error` | 서버 내부 오류 | `"서버 오류가 발생했습니다."` |

### 1.4 색상 팔레트 (색상 인덱스 0~15)

| index | name | hex | index | name | hex |
|-------|------|-----|-------|------|-----|
| 0 | White | `#FFFFFF` | 8 | Green | `#00CC78` |
| 1 | Light Gray | `#D4D7D9` | 9 | Teal | `#009EAA` |
| 2 | Dark Gray | `#898D90` | 10 | Light Blue | `#3690EA` |
| 3 | Black | `#000000` | 11 | Blue | `#0052CC` |
| 4 | Red | `#FF4500` | 12 | Indigo | `#493AC1` |
| 5 | Orange | `#FFA800` | 13 | Purple | `#811E9F` |
| 6 | Yellow | `#FFD635` | 14 | Pink | `#FF99AA` |
| 7 | Light Green | `#00A368` | 15 | Brown | `#6D482F` |

---

## 2. 엔드포인트 목록

| # | 메서드 | 경로 | 설명 | 사용 제한 |
|---|--------|------|------|-----------|
| 2.1 | `GET` | `/api/canvas/cooldown` | 픽셀 스톡 상태 조회 | - |
| 2.2 | `GET` | `/api/canvas` | 전체 캔버스 바이너리 다운로드 | - |
| 2.3 | `GET` | `/api/canvas/palette` | 색상 팔레트 조회 | - |
| 2.4 | `POST` | `/api/canvas/pixel` | 픽셀 그리기 | 스톡 1개 소모 (최대 20개, 30초당 1개 충전) |
| 2.5 | `POST` | `/api/canvas/ai-retouch` | 영역 AI 리터치 | 쿨타임 15분 |
| 2.6 | `WS` | `/ws-canvas` | 실시간 업데이트 구독 (WebSocket) | - |

---

### 2.1 픽셀 스톡 조회

`GET /api/canvas/cooldown`

현재 사용자의 픽셀 스톡(충전) 상태를 반환합니다. 스톡은 30초마다 1개씩, 최대 20개까지 충전되며 픽셀 1개를 찍을 때 1개를 소모합니다. 스톡 기록이 없는 신규 사용자는 가득 찬 상태(20개)로 간주됩니다.

> 경로는 하위 호환을 위해 `/cooldown`을 유지하지만, 반환 내용은 쿨타임이 아닌 스톡 상태입니다.

**요청 헤더**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID |

**응답 `200 OK`**

| 필드 | 타입 | 설명 |
|------|------|------|
| `stock` | number | 현재 보유 스톡 개수 (0~`maxStock`) |
| `maxStock` | number | 최대 스톡 개수 (기본 20) |
| `secondsUntilNextCharge` | number | 다음 1개 충전까지 남은 초. 가득 차면 `0` |
| `userId` | string | 사용자 UUID |

```json
{ "stock": 12, "maxStock": 20, "secondsUntilNextCharge": 8, "userId": "b1e2..." }
```

---

### 2.2 캔버스 다운로드

`GET /api/canvas`

512×512 캔버스 전체를 바이너리(`application/octet-stream`)로 반환합니다.

**응답 `200 OK`** — `Content-Type: application/octet-stream`

- 본문: **262,144바이트** 배열.
- 각 바이트 = 색상 인덱스(0~15).
- 좌표 → 인덱스 변환: `byteIndex = y * 512 + x`

> 캔버스가 아직 초기화되지 않은 경우 전부 `0`(White)인 빈 캔버스를 반환합니다.

---

### 2.3 팔레트 조회

`GET /api/canvas/palette`

사용 가능한 16색 목록을 반환합니다.

**응답 `200 OK`**

```json
[
  { "index": 0, "name": "White", "hex": "#FFFFFF" },
  { "index": 1, "name": "Light Gray", "hex": "#D4D7D9" }
]
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `index` | number (byte) | 색상 인덱스 (0~15) |
| `name` | string | 색상 이름 |
| `hex` | string | HEX 색상 코드 |

---

### 2.4 픽셀 그리기

`POST /api/canvas/pixel`

지정 좌표에 색상을 찍습니다. 성공 시 픽셀 스톡을 1개 소모하며, 스톡이 없으면 `429`를 반환합니다. 스톡은 30초마다 1개씩 최대 20개까지 충전됩니다.

**요청 헤더**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID |

**요청 바디**

| 필드 | 타입 | 필수 | 제약 | 설명 |
|------|------|------|------|------|
| `x` | number | ✔ | 0~511 | X 좌표 |
| `y` | number | ✔ | 0~511 | Y 좌표 |
| `color` | number (byte) | ✔ | 0~15 | 색상 인덱스 |

```json
{ "x": 100, "y": 200, "color": 3 }
```

**응답 `200 OK`**

| 필드 | 타입 | 설명 |
|------|------|------|
| `status` | string | `"success"` |
| `offset` | number | 기록된 캔버스 오프셋 (`y*512+x`) |
| `stock` | number | 소모 후 남은 스톡 개수 |
| `maxStock` | number | 최대 스톡 개수 (기본 20) |
| `secondsUntilNextCharge` | number | 다음 1개 충전까지 남은 초. 가득 차면 `0` |
| `userId` | string | 사용자 UUID |

```json
{ "status": "success", "offset": 102500, "stock": 11, "maxStock": 20, "secondsUntilNextCharge": 30, "userId": "b1e2..." }
```

**오류 응답**

| 상태 코드 | 조건 | 본문 |
|-----------|------|------|
| `400` | 좌표가 0~511 범위 밖 | `{ "error": "Out of bounds" }` |
| `400` | 색상 인덱스가 0~15 범위 밖 | `{ "error": "Invalid color index" }` |
| `429` | 스톡 부족 | `{ "error": "No stock", "stock": 0, "secondsUntilNextCharge": 8, "userId": "..." }` |
| `500` | Redis 기록 실패 | (본문 없음, 차감된 스톡은 자동 환불) |

---

### 2.5 AI 리터치

`POST /api/canvas/ai-retouch`

지정 영역(8×8 ~ 16×16)의 픽셀을 AI로 리터치합니다. 성공 시 쿨타임(기본 15분)이 설정됩니다.

**요청 헤더**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID |

**요청 바디**

| 필드 | 타입 | 필수 | 제약 | 기본값 | 설명 |
|------|------|------|------|--------|------|
| `x` | number | ✔ | 0~511 | - | 영역 좌상단 X 좌표 |
| `y` | number | ✔ | 0~511 | - | 영역 좌상단 Y 좌표 |
| `width` | number | | 8~16 | 16 | 영역 너비 |
| `height` | number | | 8~16 | 16 | 영역 높이 |
| `prompt` | string | | - | `""` | AI 리터치 지시문 |

> 영역이 캔버스 경계를 벗어나면 안 됩니다: `x + width ≤ 512`, `y + height ≤ 512`.

```json
{ "x": 0, "y": 0, "width": 16, "height": 16, "prompt": "Make this look like a sunset sky" }
```

**응답 `200 OK`**

```json
{ "status": "success", "userId": "b1e2..." }
```

**오류 응답**

| 상태 코드 | 조건 | 본문 |
|-----------|------|------|
| `400` | `width`/`height`가 8~16 범위 밖 | `{ "error": "Region size must be between 8 and 16" }` |
| `400` | 영역이 캔버스 경계 초과 | `{ "error": "Region exceeds canvas bounds" }` |
| `429` | 쿨타임 중 | `{ "error": "Cooldown active", "remainingSeconds": 800, "userId": "..." }` |

---

### 2.6 실시간 업데이트 구독 (WebSocket)

`WS /ws-canvas`

캔버스 변경 사항을 실시간으로 수신하는 WebSocket 엔드포인트입니다. 클라이언트는 별도 메시지를 보낼 필요가 없으며, 서버가 변경 이벤트를 텍스트 프레임으로 푸시합니다. 서버는 30초마다 Ping 프레임을 보냅니다.

**수신 메시지 유형**

**① 픽셀 업데이트** (단일 픽셀 그리기 시)

```json
{ "x": 100, "y": 200, "color": 3, "userId": "b1e2..." }
```

**② 영역 업데이트** (AI 리터치 시)

| 필드 | 타입 | 설명 |
|------|------|------|
| `type` | string | `"REGION_UPDATE"` (영역 업데이트 식별자) |
| `x`, `y` | number | 영역 좌상단 좌표 |
| `width`, `height` | number | 영역 크기 |
| `pixels` | number[] | 색상 인덱스 배열 (길이 = width×height, 행 우선 순서) |
| `userId` | string | 변경한 사용자 UUID |

```json
{
  "type": "REGION_UPDATE",
  "x": 0, "y": 0, "width": 16, "height": 16,
  "pixels": [3, 3, 4, 5, ...],
  "userId": "b1e2..."
}
```

> **메시지 구분 방법**: `type` 필드가 있으면 영역 업데이트, 없으면 단일 픽셀 업데이트로 처리하세요.

---

## 부록 A. 엔드포인트 추가 템플릿

새 엔드포인트를 추가할 때 아래 블록을 복사해 [2. 엔드포인트 목록](#2-엔드포인트-목록) 표에 한 줄 추가하고, 상세 섹션을 작성하세요.

```markdown
### 2.X {엔드포인트 이름}

`{METHOD} {경로}`

{한 줄 설명 + 쿨타임/부수효과 명시}

**요청 헤더**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID |

**요청 파라미터 / 바디**

| 필드 | 타입 | 필수 | 제약 | 기본값 | 설명 |
|------|------|------|------|--------|------|
|      |      |      |      |        |      |

**응답 `200 OK`**

| 필드 | 타입 | 설명 |
|------|------|------|
|      |      |      |

**오류 응답**

| 상태 코드 | 조건 | 본문 |
|-----------|------|------|
|           |      |      |
```

## 부록 B. 확장 가이드라인

향후 확장을 고려한 권장 규칙입니다.

- **버전 관리**: 하위 호환이 깨지는 변경이 필요하면 경로에 버전을 둡니다 → `/api/v2/canvas/...`. 현재는 무버전(`/api/canvas`)이며 이는 `v1`로 간주합니다.
- **응답 포맷 통일**: 새 엔드포인트는 성공 시 `{ "status": ..., "data": ... }`, 실패 시 `{ "error": ... }` 형태로 통일하는 것을 권장합니다. (기존 엔드포인트는 필드가 평면 구조이므로 하위 호환 유지)
- **오류 코드 체계화**: 메시지 문자열 대신 `errorCode`(예: `COOLDOWN_ACTIVE`, `OUT_OF_BOUNDS`)를 함께 반환하면 클라이언트 분기가 쉬워집니다.
- **페이지네이션 / 필터**: 목록형 엔드포인트를 추가할 경우 `?page=`, `?size=`, `?sort=` 쿼리 규약을 통일하세요.
- **WebSocket 메시지 확장**: 새 이벤트 유형은 반드시 `type` 필드로 식별하도록 하고, 유형 상수를 부록에 등록하세요.
- **인증 도입 시**: 현재의 `X-User-Id` 헤더 방식을 유지하되, `Authorization: Bearer <token>` 헤더를 상위 인증 수단으로 추가하는 방향을 권장합니다.
