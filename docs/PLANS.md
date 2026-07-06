# PLANS.md

## 현재 상태

| # | Task | 상태 | 비고 |
|---|------|------|------|
| 1 | 프로젝트 초기 세팅 (Spring Boot + Redis + WebSocket) | `done` | |
| 2 | 픽셀 찍기 REST API + 쿨타임 | `done` | |
| 3 | WebSocket 실시간 브로드캐스트 | `done` | |
| 4 | AI 리터치 엔드포인트 (스텁) | `done` | `StubAIRetouchService` |
| 5 | AI 리터치 실제 구현 | `reverted` | Hugging Face Qwen3-4B 연동 제거, 다른 AI 모델로 재구현 예정. `StubAIRetouchService`로 복귀 |
| 6 | Swagger(OpenAPI) 문서 추가 | `done` | 개발 환경 전용, `application-prod.yml`로 비활성화 |

## 작업 로그

### 2025-05
- 프로젝트 초기 커밋: Spring Boot 3 + WebFlux + Redis 기반 픽셀 캔버스 구현
- `AIRetouchService` 인터페이스 분리 및 스텁 구현체 추가
- `SPEC.md`, `CLAUDE.md`, `AGENTS.md`, `docs/` 문서 정비

### 2026-06 (Hugging Face 제거)
- `HuggingFaceService`, `HuggingFaceAIRetouchService`, `spring.ai.huggingface` 설정 삭제
- 다른 AI 모델로 교체할 예정이라 우선 `StubAIRetouchService`에 `@Service`를 복원해 기본 구현체로 되돌림

### 2026-06
- Task 6 완료: SpringDoc OpenAPI 2.8.3 추가, 전체 5개 엔드포인트 및 DTO에 어노테이션 적용
- `application-prod.yml`에 `springdoc.api-docs.enabled: false` 설정으로 prod 배포 시 Swagger 비노출

### 2026-05
- Task 5 완료: `HuggingFaceService`(WebClient + Qwen3-4B chat completions) 및 `HuggingFaceAIRetouchService`(`@Primary`) 구현
- `RetouchRequest`에 `prompt` 필드 추가 — 사용자가 직접 프롬프트 전달
- `StubAIRetouchService`의 `@Service` 제거, AI 범위 8×8~16×16 검증은 컨트롤러에서 처리
- 응답 파싱 실패 시 원본 픽셀 반환(폴백) 방식 적용
