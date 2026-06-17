# STYLEGUIDE.md

## 언어 및 프레임워크

- 모든 신규 파일은 Kotlin으로 작성합니다. (`NonePlaceApplication.java`는 레거시)
- 비동기 처리는 Kotlin Coroutines(`suspend`, `awaitSingle`, `awaitSingleOrNull`)을 사용합니다. `block()`은 사용하지 않습니다.
- `Mono`/`Flux`를 반환하는 reactor 코드는 coroutine 어댑터(`kotlinx-coroutines-reactor`)로 감싸 `suspend` 함수 안에서 처리합니다.

## 네이밍

- 클래스: `PascalCase`
- 함수·변수: `camelCase`
- Redis 키 상수: `UPPER_SNAKE_CASE` (예: `CANVAS_KEY`, `CANVAS_CHANNEL`)
- 테스트 함수명: 백틱 한글 설명 (예: `` `쿨타임 등록 후 남은 시간이 존재` ``)

## DTO

- 요청 DTO는 `data class`로 선언하고 `present/dto/` 에 둡니다.
- 응답은 별도 DTO 클래스 없이 `Map<String, Any>`로 인라인 구성합니다. 응답 구조가 복잡해지면 DTO로 분리합니다.

## 예외 처리

- 도메인 유효성 실패는 `IllegalArgumentException`을 던집니다. `GlobalExceptionHandler`가 400으로 변환합니다.
- 서비스 레이어에서 HTTP 상태를 직접 결정하지 않습니다.
