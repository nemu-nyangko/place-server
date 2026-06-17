# NonePlace API 명세서

## 개요

Reddit r/place를 모방한 협업 픽셀 캔버스 서비스.  
512×512 크기의 캔버스에 사용자가 픽셀을 찍고, 변경사항이 WebSocket으로 실시간 전파됩니다.

### 기술 스택

| 분류 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3, Spring WebFlux (Kotlin Coroutines) |
| 저장소 | Redis 7 (비트맵 저장 + Pub/Sub 브로드캐스트) |
| 실시간 통신 | WebSocket (Spring Reactive WebSocket) |
| 인프라 | Docker Compose |

---

## 사용자 식별

로그인/회원가입 없이 UUID 기반으로 사용자를 식별합니다.

- 요청 시 `X-User-Id` 헤더에 UUID를 포함하면 해당 ID를 사용
- 헤더가 없거나 유효하지 않은 UUID면 서버가 신규 UUID를 발급
- 발급된 UUID는 응답의 `X-User-Id` 헤더와 응답 바디의 `userId` 필드로 반환
- 클라이언트는 이 UUID를 저장해두고 이후 요청에 재사용해야 쿨타임이 유지됨

---

## REST API

### Base URL
```
http://localhost:8080/api/canvas
```

---

### 1. 캔버스 다운로드

```
GET /api/canvas
```

현재 캔버스 전체를 바이너리로 반환합니다.

**Response**

| 항목 | 값 |
|------|----|
| Content-Type | `application/octet-stream` |
| 크기 | 262,144 bytes (512 × 512) |

각 바이트는 해당 픽셀의 색상 인덱스(0~15)를 나타냅니다.  
오프셋 계산: `offset = y * 512 + x`

**Example**
```
GET /api/canvas
→ 200 OK
→ [262144 bytes binary]
```

---

### 2. 픽셀 찍기

```
POST /api/canvas/pixel
```

지정한 좌표에 픽셀을 찍습니다. 성공 시 쿨타임이 시작됩니다.

**Request Headers**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID. 없으면 신규 발급 |
| `Content-Type` | 필수 | `application/json` |

**Request Body**

```json
{
  "x": 10,
  "y": 20,
  "color": 4
}
```

| 필드 | 타입 | 범위 | 설명 |
|------|------|------|------|
| `x` | Int | 0 ~ 511 | 가로 좌표 |
| `y` | Int | 0 ~ 511 | 세로 좌표 |
| `color` | Byte | 0 ~ 15 | 색상 인덱스 |

**Response**

성공 `200 OK`
```json
{
  "status": "success",
  "offset": 10250,
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

응답 헤더에도 `X-User-Id`가 포함됩니다.

**Error Responses**

| 상태코드 | 조건 | 응답 |
|----------|------|------|
| `400` | 좌표 범위 초과 | `{"error": "Out of bounds"}` |
| `400` | 유효하지 않은 색상 인덱스 | `{"error": "Invalid color index"}` |
| `429` | 쿨타임 중 재요청 | `{"error": "Cooldown active", "remainingSeconds": 293, "userId": "..."}` |
| `500` | 서버 내부 오류 | `{"error": "서버 오류가 발생했습니다."}` |

---

### 3. 쿨타임 조회

```
GET /api/canvas/cooldown
```

현재 사용자의 쿨타임 상태를 조회합니다.

**Request Headers**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | 선택 | 사용자 UUID. 없으면 신규 발급 |

**Response** `200 OK`

```json
{
  "active": true,
  "remainingSeconds": 293,
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `active` | Boolean | 쿨타임 진행 중 여부 |
| `remainingSeconds` | Long | 남은 쿨타임 (초). 쿨타임 없으면 0 |
| `userId` | String | 사용자 UUID |

응답 헤더에도 `X-User-Id`가 포함됩니다.

---

### 4. 팔레트 조회

```
GET /api/canvas/palette
```

사용 가능한 색상 목록을 반환합니다.

**Response** `200 OK`

```json
[
  { "index": 0,  "name": "White",       "hex": "#FFFFFF" },
  { "index": 1,  "name": "Light Gray",  "hex": "#D4D7D9" },
  { "index": 2,  "name": "Dark Gray",   "hex": "#898D90" },
  { "index": 3,  "name": "Black",       "hex": "#000000" },
  { "index": 4,  "name": "Red",         "hex": "#FF4500" },
  { "index": 5,  "name": "Orange",      "hex": "#FFA800" },
  { "index": 6,  "name": "Yellow",      "hex": "#FFD635" },
  { "index": 7,  "name": "Light Green", "hex": "#00A368" },
  { "index": 8,  "name": "Green",       "hex": "#00CC78" },
  { "index": 9,  "name": "Teal",        "hex": "#009EAA" },
  { "index": 10, "name": "Light Blue",  "hex": "#3690EA" },
  { "index": 11, "name": "Blue",        "hex": "#0052CC" },
  { "index": 12, "name": "Indigo",      "hex": "#493AC1" },
  { "index": 13, "name": "Purple",      "hex": "#811E9F" },
  { "index": 14, "name": "Pink",        "hex": "#FF99AA" },
  { "index": 15, "name": "Brown",       "hex": "#6D482F" }
]
```

---

## WebSocket API

### 연결

```
ws://localhost:8080/ws-canvas
```

연결 후 별도 메시지 송신 없이 서버로부터 픽셀 업데이트를 수신합니다.

### 수신 메시지 형식

픽셀이 찍힐 때마다 Text 프레임으로 전송됩니다.

```json
{"x": 10, "y": 20, "color": 4, "userId": "550e8400-e29b-41d4-a716-446655440000"}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `x` | Int | 가로 좌표 |
| `y` | Int | 세로 좌표 |
| `color` | Int | 색상 인덱스 (0~15) |
| `userId` | String | 픽셀을 찍은 사용자 UUID |

### Ping/Pong

연결 유지를 위해 서버가 30초마다 WebSocket Ping 프레임을 전송합니다.  
클라이언트는 Pong으로 응답해야 합니다 (대부분의 브라우저/라이브러리가 자동 처리).

---

## 비즈니스 규칙

### 쿨타임

- 픽셀을 성공적으로 찍으면 **5분(300초)** 쿨타임 시작
- 쿨타임 중 재요청 시 `429 Too Many Requests` 반환
- 쿨타임은 `X-User-Id` 기준으로 적용

### 캔버스

- 크기: **512 × 512 픽셀**
- 서버 최초 실행 시 모든 픽셀이 흰색(인덱스 0)으로 초기화
- 이미 데이터가 있으면 기존 데이터 유지 (재시작 후에도 보존)
- Redis AOF(Append Only File)로 영속성 보장

---

## CORS

| 허용 Origin | `http://localhost:3000`, `http://localhost:5173` |
|-------------|--------------------------------------------------|
| 허용 메서드 | `GET`, `POST`, `OPTIONS` |
| 허용 헤더 | `Content-Type`, `X-User-Id` |
| 노출 헤더 | `X-User-Id` |

---

## 실행 방법

### Redis 실행

```bash
docker-compose up -d
```

### 서버 실행

```bash
./gradlew bootRun
```
