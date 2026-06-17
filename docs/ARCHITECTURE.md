# ARCHITECTURE.md

## 폴더 규칙

```
domain/<도메인명>/
  present/     컨트롤러, WebSocket 핸들러, DTO
  service/     비즈니스 로직, Redis 연산
  config/      도메인 전용 설정 (WebSocket 매핑, Palette 상수 등)

global/
  config/      전역 설정 (CORS)
  exception/   전역 예외 처리
  redis/config/ Redis 빈 등록
```

## 레이어 원칙

- `present` → `service` 방향으로만 참조합니다.
- `service`는 `ResponseEntity` 등 HTTP 타입을 사용하지 않습니다.
- `global`은 `domain`을 참조하지 않습니다.
- 도메인 간 직접 참조는 하지 않습니다. (`CanvasController`가 `UserService`를 직접 주입하는 방식 허용)

## 구조 원칙

- 인터페이스와 구현체를 분리해야 하는 경우에만 인터페이스를 만듭니다. 현재는 `AIRetouchService`만 해당합니다.
- 설정값은 `application.yml`에 두고 `@Value` 또는 `@ConfigurationProperties`로 주입합니다.
- 새 도메인 추가 시 `domain/<이름>/` 하위에 동일한 구조로 만듭니다.
