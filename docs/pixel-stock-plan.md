# 픽셀 스톡(충전) 시스템 전환 스펙

## 개요

기존 픽셀 그리기의 **단일 5분 쿨타임**을 **충전형 스톡(토큰 버킷)** 방식으로 전환한다.

- 유저는 최대 **20개**의 픽셀 스톡을 보유한다.
- **30초마다 1개**씩 충전된다 (0 → 20 가득 차는 데 10분).
- 픽셀 1개를 찍으면 스톡 1개를 소모한다.
- 스톡이 0이면 그리기 불가(429).

## 결정 사항 요약 (인터뷰 결과)

| # | 주제 | 결정 |
|---|------|------|
| 1 | 충전 파라미터 | 새 설정값 `pixel-regen-seconds: 30`, `pixel-max-stock: 20` 추가. 기존 `cooldown-seconds`는 픽셀 로직에서 미사용 |
| 2 | 저장 구조 | Redis Hash `{stock, lastRefillAt}` + 읽을 때 지연 계산(lazy). 기준시각은 충전된 개수×30초만큼만 전진 |
| 3 | 신규 유저 초기 스톡 | 가득 찬 20개 (키 없으면 stock=max로 간주) |
| 4 | 동시성 | Lua 스크립트로 충전 계산+차감+저장을 원자 실행 |
| 5 | 조회 API | `GET /api/canvas/cooldown` 경로 유지, 응답을 스톡 필드로 교체 |
| 6 | 그리기 응답 | 성공(200)·스톡부족(429) 모두 스톡 정보 포함 |
| 7 | 키 TTL | 픽셀 그릴 때 `EXPIRE 600`으로 갱신(그만둔 유저 자동 정리) |
| 8 | 서비스 구조 | `PixelStockService` 신설. `CooldownService`는 AI 리터치 전용으로 남김 |
| 9 | 실시간 갱신 | 프론트 로컬 계산. WebSocket 서버 변경 없음 |

## 데이터 모델

**Redis Key:** `pixel-stock:<userId>` (Hash)

| 필드 | 의미 |
|------|------|
| `stock` | 마지막 갱신 시점의 스톡 개수 |
| `lastRefillAt` | 마지막 충전 기준 시각 (epoch seconds) |

**지연 계산 로직 (읽기/차감 공통):**
```
elapsed = now - lastRefillAt
gained  = floor(elapsed / regenSeconds)
newStock = min(maxStock, stock + gained)

if newStock >= maxStock:
    newStock = maxStock
    lastRefillAt = now            # 가득 참: 시간 적립 방지 위해 now로 스냅
else:
    lastRefillAt = lastRefillAt + gained * regenSeconds   # 부분 진행률 보존
```

**신규 유저:** 키가 없으면 `stock = maxStock`, `lastRefillAt = now`로 간주.

**다음 충전까지 남은 초 (secondsUntilNextCharge):**
```
if stock >= maxStock: 0
else: regenSeconds - ((now - lastRefillAt) % regenSeconds)
```

## 설정 (application.yml)

```yaml
canvas:
  pixel-regen-seconds: 30            # 픽셀 스톡 충전 간격 (초)
  pixel-max-stock: 20                # 최대 스톡 개수
  ai-retouch-cooldown-seconds: 900   # AI 리터칭 쿨타임 (초) — 변경 없음
  # cooldown-seconds: 300            # (픽셀 로직에서 더 이상 사용 안 함)
```

## 원자적 소모 — Lua 스크립트

`PixelStockService`가 보유. `EVAL`로 실행하며 KEYS[1]=stock key, ARGV=[now, regenSeconds, maxStock, ttl].

동작:
1. HGET으로 현재 `stock`, `lastRefillAt` 읽기 (키 없으면 stock=max, lastRefillAt=now)
2. 지연 계산으로 현재 스톡 산출
3. 현재 스톡 < 1 이면 → 차감 없이 실패 신호 반환 (예: `{ -1, secondsUntilNextCharge }`)
4. 스톡 >= 1 이면 → stock-1, lastRefillAt 갱신 후 HSET + `EXPIRE key ttl`
5. 새 stock, secondsUntilNextCharge 반환

조회 전용(비차감) 경로도 필요: 스톡 상태만 계산해 반환(HSET 없이). 별도 Lua 또는 서비스 코드에서 읽어 계산.

## API 변경

### POST /api/canvas/pixel

흐름: userId 확정 → 좌표/색상 검증 → **원자적 스톡 차감(Lua)** → 성공 시 `canvasService.updatePixel` → (쓰기 실패 시 스톡 +1 best-effort 환불)

**성공 (200):**
```json
{ "status": "success", "offset": 5130, "stock": 11,
  "maxStock": 20, "secondsUntilNextCharge": 30, "userId": "..." }
```

**스톡 부족 (429):**
```json
{ "error": "No stock", "stock": 0,
  "secondsUntilNextCharge": 8, "userId": "..." }
```

**검증 실패 (400):** 기존과 동일 (`Out of bounds`, `Invalid color index`)

> 순서 주의: 스톡을 먼저 원자적으로 차감하고 픽셀을 쓴다. `updatePixel`이 null(쓰기 실패) 반환 시 스톡을 1 되돌리고 500 반환(SETRANGE 실패는 사실상 없는 엣지 케이스).

### GET /api/canvas/cooldown

경로 유지, 응답만 교체:
```json
{ "stock": 12, "maxStock": 20, "secondsUntilNextCharge": 8, "userId": "..." }
```
(비차감 조회 — 스톡을 소모하지 않음)

### POST /api/canvas/ai-retouch

**변경 없음.** `CooldownService`의 AI 리터치 전용 쿨타임(`ai-retouch-cooldown:<userId>`, 900초) 그대로 사용.

## 코드 변경 대상

| 파일 | 변경 |
|------|------|
| `service/PixelStockService.kt` | **신설.** Lua 스크립트 보유, `consumeStock(userId)` / `getStock(userId)` 제공. 반환 DTO로 stock/maxStock/secondsUntilNextCharge 전달 |
| `service/CooldownService.kt` | 픽셀 관련 메서드(`getRemainingCooldown`, `setCooldown`) 및 `cooldown-seconds` 설정 제거. AI 리터치 전용으로 축소 |
| `present/CanvasController.kt` | `drawPixel`: 쿨타임 체크 → 스톡 차감으로 교체, 응답 필드 변경. `getCooldown`: 스톡 조회로 교체 |
| `resources/application.yml` | `pixel-regen-seconds`, `pixel-max-stock` 추가. `cooldown-seconds` 주석 처리/제거 |
| (선택) `resources/lua/consume-stock.lua` | Lua 스크립트를 리소스 파일로 관리하거나 서비스 내 문자열 상수로 보유 |

## 테스트 변경 대상

| 파일 | 변경 |
|------|------|
| `service/CooldownServiceTest.kt` | 픽셀 쿨타임 관련 테스트 제거/이동. AI 리터치 케이스만 유지 |
| `service/PixelStockServiceTest.kt` | **신설.** 신규 유저=20개, 1개 차감 시 19개, 0개일 때 실패, 시간 경과 후 충전(TestRedis에서 lastRefillAt 조작), 상한 캡, 연속 차감 원자성 |
| `present/CanvasControllerTest.kt` | ① `쿨타임 중 재요청 시 429` → 스톡 20개라 연속 성공하므로 **20회 소진 후 429**로 수정, 응답 필드 `error:"No stock"`, `stock`, `secondsUntilNextCharge`로 변경. ② `쿨타임 없을 때/찍은 후` 테스트 → `/cooldown` 응답을 `stock`/`maxStock` 검증으로 변경. ③ `픽셀 찍기 성공` → `stock` 필드 추가 검증 |

## 미해결/후속 (범위 외)

- 프론트엔드: 응답의 `stock` + `secondsUntilNextCharge`로 로컬 타이머를 돌려 충전 애니메이션 표시 (서버 무관, 별도 작업).
- `cooldown-seconds` 설정을 완전히 삭제할지, `prod` 프로파일까지 정리할지는 구현 시 확인.
