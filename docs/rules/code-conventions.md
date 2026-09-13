# 코드

코드 작성 규칙입니다 — 코딩 스타일 · Spring · DTO · 로깅 · 테스트.

## 코딩 스타일

포맷은 도구로 강제하고, 도구가 못 잡는 명명·관용은 아래 규칙을 따릅니다.

### 포맷

- **Spotless + Google Java Format** (2-space, 100칸)으로 강제합니다.
- 커밋 전 `./gradlew spotlessApply`로 정렬하고, CI가 `./gradlew spotlessCheck`로 검사합니다.
- 에디터 설정은 `.editorconfig`를 따릅니다.
- 줄바꿈 형식은 `.gitattributes`를 기준으로 LF로 통일하며, `gradlew.bat`만 CRLF 예외로
  처리합니다.
- GJF가 자동 처리하므로 따로 신경 쓰지 않아도 되는 것: 와일드카드 import 금지, 모든
  제어문 중괄호, 연산자 공백, 배열 Java식(`String[]`).

### 네이밍

- 클래스: `XxxController` / `XxxService` / `XxxRepository`, 엔티티 `User`,
  DTO `XxxRequest` / `XxxResponse`.
- 메서드: 조회 `getX`·`findXById`·`findXList`, 생성 `createX`·`saveX`,
  수정 `updateX`·`modifyX`, 삭제 `deleteX`·`removeX`, 검증 `validateX`·`checkXExists`.
- 변수: camelCase. boolean 은 `isActive`·`hasPermission`. 컬렉션은 복수형(`users`).
- 상수: `UPPER_SNAKE_CASE`.

### Lombok

- 허용: `@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`,
  `@Builder`, `@Slf4j`.
- 지양: `@Data`, 무분별한 `@Setter`.

### 기타

- `switch` 에는 `default` 를 둡니다.
- 불리언은 직접 평가합니다: `if (isActive)` (O), `if (isActive == true)` (X).

## Spring

### 의존성 주입

- 생성자 주입만 사용합니다(필드 주입 금지). `@RequiredArgsConstructor` + `private final`을 권장합니다.

### 레이어 책임

- Controller: HTTP 입출력에 집중하고 얇게 유지합니다.
- OpenAPI 문서화는 [서버 명세 > OpenAPI](../spec/openapi.md)를 따릅니다.
- 모든 API의 path(`/api` prefix)와 버저닝은 [서버 명세 > API 버저닝](../spec/api-versioning.md)을 따릅니다.
- Service: 오케스트레이션·교차 엔티티 규칙·트랜잭션 경계.
- Entity: 자기 상태·불변식 로직(자신을 어떻게 바꿀지)을 담습니다.
- Repository: DB 접근을 캡슐화합니다(그 뒤로 숨김). 초기 접근은 JPA.
- 공통 인프라 코드는 platform 성격의 위치에 둡니다.

### 검증

- 요청 DTO에는 validation annotation을 사용합니다.

### 트랜잭션

- 트랜잭션 경계는 Service 메서드에 둡니다.

### 보안

- Spring Security 의존성은 초기부터 포함하되, 보안 설정 클래스는 인증/인가 구현 시점에 만듭니다.

## 보호 API (인증 · 현재 사용자)

도메인 모듈이 보호 API를 작성할 때 auth 내부(JWT 디코더·쿠키·필터)를 알 필요가 없도록, 아래 계약만 따릅니다.

### 현재 사용자 식별자

- 컨트롤러는 `java.security.Principal`을 받아 `CurrentUser.id(principal)`(`common.api`)로 userId(UUID)를 얻습니다.
- `Principal.getName()`이 userId라는 계약과 인증 수단(Bearer/쿠키) 중립성은 auth가 보장합니다. 컨트롤러는 토큰·쿠키를
  직접 다루지 않습니다.
- `principal`이 null이면 `AUTH_UNAUTHORIZED`, 이름이 UUID가 아니면 `AUTH_INVALID_TOKEN`을 `CurrentUser.id`가 던집니다.

### 기본 보호 · 공개 경로

- 보호 체인이 `anyRequest().authenticated()`라 **새 엔드포인트는 기본으로 보호**됩니다. 인증된 요청만 컨트롤러에 도달합니다.
- 공개로 열어야 하는 경로는 auth의 `SecurityConfig.PUBLIC_PATHS`에 추가합니다(= auth 소유). 도메인 모듈에서 보안 설정을
  만들지 않습니다.

### 401 · 403

- 인증/인가 거부 본문은 필터가 Standard 에러 shape로 자동 emit합니다. 컨트롤러는 401/403을 직접 처리하지 않습니다.
  - 인증 정보 없음 → `AUTH_UNAUTHORIZED` (401)
  - 토큰 무효·만료·형식 오류 → `AUTH_INVALID_TOKEN` (401)
  - 인가 거부 → `AUTH_FORBIDDEN` (403)
- 요청자의 권한(RBAC)은 컨트롤러가 있는 `web`과 `mobile` 모듈의 조합 서비스에서 확인합니다(현재 사용자는 `userId`만 신뢰). 도메인 모듈의 쓰기 public API는 요청자의 권한을 확인하지 않으며, 호출하는 쪽에서 권한을 확인한 뒤 확정한 `workspaceId`를 전달받습니다.
- 워크스페이스 권한 부족으로 `AUTH_FORBIDDEN`을 반환할 때 포함하는 `details`는 [서버 명세 > API 응답과 에러 코드](../spec/api-response-error-codes.md)의 「권한 details」 절을 따릅니다. `web` 모듈에서는 `WorkspaceAccessChecker`가 이 형식을 관리하며, 다른 클래스가 권한 거부 응답을 직접 생성하지 않는지는 `WebForbiddenResponseBoundaryTests`에서 검증한다.

### 테스트 인증 주입

- 슬라이스(`@WebMvcTest(addFilters = false)`): 보안 필터가 없으니 `Principal`을 직접 주입합니다.

  ```java
  private final Principal principal = USER_ID::toString; // getName() = userId
  mockMvc.perform(get("/api/things").principal(principal));
  ```

- 통합(`@SpringBootTest` + `AbstractPostgresIntegrationTest`): auth의 public testFixtures `AccessTokenTestFactory`로
  실토큰을 발급해 헤더/쿠키로 보냅니다.

  ```java
  @Autowired AccessTokenTestFactory accessTokens;
  String token = accessTokens.issueAccessToken(userId);
  mockMvc.perform(get("/api/things").header("Authorization", "Bearer " + token));
  ```

### 예제 (보호 컨트롤러)

```java
@RestController
@RequiredArgsConstructor
class ThingController {

  private final ThingService thingService;

  @GetMapping("/api/things/{id}")
  ThingResponse get(Principal principal, @PathVariable UUID id) {
    UUID userId = CurrentUser.id(principal); // 인증 수단 중립, auth 내부 무관
    return ThingResponse.from(thingService.get(userId, id));
  }
}
```

## DTO

- request/response DTO는 Java record를 기본으로 합니다. class는 다음일 때만 사용합니다:
  기본 생성자 필요 / Jackson·JPA·외부 라이브러리 호환 / 기본값·복잡한 생성 로직.
- API DTO와 persistence 모델(엔티티)은 분리합니다.
- request/response DTO는 분리해서 둡니다. 기능 모듈의 presentation 레이어에 두며(모듈 구조는
  [ADR-0002](../adr/0002-gradle-multi-module-boundaries.md)), 모듈 내 구체 경로는 해당 모듈을
  만들며 확정합니다.
- API 성공 응답에는 전역 wrapper(`success`, `data`)를 두지 않습니다. 응답/에러 코드
  규격은 [서버 명세 > API 응답과 에러 코드](../spec/api-response-error-codes.md)를 따릅니다.
- JSON wire format은 snake_case이며, 소유자는 전역 설정 하나입니다: app
  `application.yml`의 `spring.jackson.property-naming-strategy: SNAKE_CASE`.
  DTO별 `@JsonNaming`은 두지 않습니다.
  - 기능 모듈 slice test는 app 설정을 읽지 않으므로, presentation DTO가 있는 모듈은
    `src/test/resources/application.yml`에 같은 키를 둡니다.
  - 외부 API 클라이언트(RestClient 등) DTO도 전역 전략을 타므로, camelCase wire를 쓰는
    외부 API는 `@JsonProperty`로 필드별 이름을 명시합니다.
  - 문서 쪽(swagger-core, Jackson 2)은 이 설정을 읽지 못해 `OpenApiConfig`의
    `ModelResolver`가 같은 전략을 별도로 겁니다.

### 네이밍

- 요청: `{기능}Request` (예: `CreatePostRequest`)
- 응답: `{기능}Response` (예: `PostResponse`)

### 요청 DTO

- `@Schema`로 문서화합니다.
- 검증 annotation을 사용합니다(`@NotBlank`, `@NotNull`, `@Size` 등).
- 컬렉션 원소도 검증합니다: `List<@NotBlank String>`.

### 응답 DTO

- record + `@Schema`. 단순 생성은 생성자를, 정적 팩토리 메서드는 생성 의도를 드러내거나
  로직을 캡슐화할 때만 사용합니다.

## 로깅

- 로거는 Lombok `@Slf4j`를 사용합니다.
- 로그 인자는 문자열 연결 대신 `{}` placeholder로 전달합니다
  (`log.info("user {} created", userId)`).
- 민감 정보(비밀번호·토큰·개인정보 등)는 로그에 남기지 않습니다([설정 · 시크릿 > 시크릿](configuration.md#시크릿) 참고).
- 로그 상관관계는 [관측성](observability.md)의 traceId/spanId 기준을 따릅니다.
- 검색 가능한 로그를 위해 메시지에 주요 도메인 식별자를 명시적으로 남깁니다. 예:
  `log.info("workspace created workspaceId={} userId={}", workspaceId, userId)`.
- 로그 레벨:
  - `error`: 시스템 오류·예외
  - `warn`: 경고·복구 가능한 문제
  - `info`: 주요 비즈니스 흐름
  - `debug`: 개발 디버깅

## 테스트

테스트 프레임워크는 JUnit 5를 사용합니다. 테스트 유형:

- 애플리케이션 컨텍스트 로드 테스트
- Spring Modulith 경계 테스트
- controller / web 테스트
- service 단위 테스트
- repository 통합 테스트 — PostgreSQL Testcontainers 사용
  ([데이터 > 영속성 > DB](persistence.md#db) 참고, H2 미사용)
