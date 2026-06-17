# TESTING.md

## 테스트 전략

| 대상 | 방식 |
|------|------|
| `UserService` 등 Redis 비의존 서비스 | 순수 단위 테스트 (스프링 컨텍스트 없음) |
| `CanvasService`, `CooldownService` | `@SpringBootTest` + 임베디드 Redis |
| 컨트롤러 | `@SpringBootTest` + 임베디드 Redis |

## 임베디드 Redis 사용법

테스트 클래스에 아래 두 어노테이션을 추가합니다.

```kotlin
@SpringBootTest
@Import(TestRedisConfig::class)
class MyServiceTest {
    ...
}
```

`TestRedisConfig`는 랜덤 포트로 임베디드 Redis를 실행하고, `ReactiveRedisConnectionFactory` 빈을 `@Primary`로 등록합니다. 실제 Redis 없이 테스트가 동작합니다.

## Coroutine 테스트

`suspend` 함수는 `runTest { }` 블록 안에서 호출합니다.

```kotlin
@Test
fun `예시`() = runTest {
    val result = someService.suspendFn()
    assertNotNull(result)
}
```

## 검증 기준

- 새 서비스 로직 추가 시 정상 경로와 실패 경로를 각각 테스트합니다.
- Redis 키 구조나 TTL이 변경되면 관련 테스트도 함께 수정합니다.
- 테스트는 `./gradlew test` 로 전체 실행, 단일 클래스는 `--tests` 옵션으로 지정합니다.
