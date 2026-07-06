# Task: AI 리터치 실제 구현 (Google Gemini)

## 상태

`done`

## 목표

`StubAIRetouchService`를 Google Gemini 모델을 WebClient로 호출하는 실제 구현체로 교체한다.

## 완료 조건

- [x] `GeminiService`가 Gemini Interactions API(`v1beta/interactions`)를 WebClient로 호출할 수 있다
- [x] `GeminiAIRetouchService`가 `AIRetouchService` 인터페이스를 구현하고 `@Primary`로 등록된다
- [x] 리터치 가능 범위가 8×8(최소) ~ 16×16(최대)로 강제된다 (초과 시 400 에러)
- [x] 사용자가 `POST /api/canvas/ai-retouch` 요청 시 프롬프트를 본문에 포함할 수 있다
- [x] `application.yml`의 `gemini.api-key`(`GEMINI_API_KEY` 환경변수) / `gemini.model` / `gemini.base-url` 값이 WebClient에 주입된다
- [x] `StubAIRetouchService`의 `@Service`를 제거하거나 `@Primary`를 이동해 충돌이 없다
- [x] 기존 통합 테스트(`CanvasServiceTest` 등)가 그린(Green)을 유지한다

## 참조

- `src/main/java/org/example/noneplace/domain/canvas/service/AIRetouchService.kt` — 인터페이스
- `src/main/java/org/example/noneplace/domain/canvas/service/StubAIRetouchService.kt` — 기존 스텁
- `src/main/java/org/example/noneplace/global/ai/gemini/service/GeminiService.kt` — WebClient 호출 진입점
- `src/main/java/org/example/noneplace/global/config/WebClientConfig.kt` — WebClient 빈 설정
- `src/main/resources/application.yml` — `gemini.api-key`, `gemini.model`, `gemini.base-url`
- `src/main/java/org/example/noneplace/domain/canvas/present/CanvasController.kt` — `/api/canvas/ai-retouch` 엔드포인트
- `src/main/java/org/example/noneplace/domain/canvas/present/dto/RetouchRequest.kt` — 요청 DTO

## 결정 사항

- **프롬프트 설계는 사용자 담당**: API 엔드포인트는 `prompt` 필드를 그대로 모델에 전달만 한다. 서버 측에서 프롬프트를 강제 수정하지 않는다.
- **범위 검증 위치**: 컨트롤러에서 `width`/`height`를 검증 (8 ≤ width ≤ 16, 8 ≤ height ≤ 16). 서비스 레이어는 검증된 값만 받는다.
- **모델 응답 파싱**: Gemini 응답 텍스트에서 색상 인덱스 배열(0~15)을 추출하는 파싱 로직이 필요하다. 파싱 실패 시 원본 픽셀 반환한다.
- **`StubAIRetouchService` 유지 여부**: 테스트용으로 남기되 `@Service`를 제거하고 테스트에서 명시적으로 주입하는 방향을 권장.
- **API 키 관리**: `GEMINI_API_KEY` 환경변수로 주입하며, 저장소에는 절대 실제 키 값을 커밋하지 않는다.
