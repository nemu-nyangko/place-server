# Task: Swagger(OpenAPI) 문서 추가

## 상태

`done`

## 목표

SpringDoc OpenAPI를 사용해 전체 REST API에 Swagger UI를 추가하되, 개발 환경에서만 활성화한다.

## 완료 조건

- [x] `build.gradle.kts`에 `springdoc-openapi-starter-webflux-ui` 의존성 추가
- [x] `application.yml`에 `springdoc` 설정 추가, `application-prod.yml`에서 비활성화
- [x] 모든 REST 엔드포인트에 `@Operation`, `@ApiResponse` 등 OpenAPI 어노테이션 추가
- [x] DTO 클래스에 `@Schema` 어노테이션으로 필드 설명 추가
- [ ] `/swagger-ui.html`에서 UI 접근 확인 (Redis 기동 후 수동 확인 필요)
- [x] `prod` 프로파일에서는 Swagger UI 및 `/v3/api-docs` 엔드포인트가 노출되지 않음

## 참조

- `src/main/java/org/example/noneplace/domain/canvas/present/CanvasController.kt` — 전체 REST 엔드포인트
- `src/main/java/org/example/noneplace/domain/canvas/present/dto/PixelRequest.kt` — 픽셀 요청 DTO
- `src/main/java/org/example/noneplace/domain/canvas/present/dto/RetouchRequest.kt` — AI 리터치 요청 DTO
- `src/main/resources/application.yml` — 프로파일별 설정
- `build.gradle.kts` — 의존성 추가 위치

## 결정 사항

- **SpringDoc OpenAPI 선택**: Spring Boot 3 + WebFlux 조합에서 공식 지원되는 `springdoc-openapi-starter-webflux-ui` 사용 (`springfox`는 WebFlux 미지원)
- **프로파일 분리 방식**: `@Profile("dev")` 또는 `springdoc.api-docs.enabled=false`를 `application-prod.yml`에 설정하는 방식 중 하나 선택 필요
- **어노테이션 범위**: `CanvasController`의 5개 엔드포인트 및 관련 DTO 전체 커버
