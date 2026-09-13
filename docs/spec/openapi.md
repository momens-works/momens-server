# OpenAPI

이 문서는 `momens-server` HTTP JSON API의 Swagger/OpenAPI 문서화 규칙을 정의합니다.

## 원칙

- 각 공개 엔드포인트는 OpenAPI에 성공 응답과 주요 실패 응답 예시를 함께 둡니다.
- 성공 응답에는 전역 wrapper(`success`, `data`)를 만들지 않습니다.
- 실패 응답 예시는 [API 응답과 에러 코드](api-response-error-codes.md)의 응답 모드를 따릅니다.
- Swagger/OpenAPI 문서화는 실제 public contract를 설명합니다. 내부 구현 세부사항을 노출하지 않습니다.
- 모든 path는 `/api` prefix로 문서화합니다. 레거시 path alias는 두지 않습니다.

## 구성 위치

OpenAPI 공통 설정과 커스터마이저는 실행·조립 모듈인 `app`에 둡니다.

```text
app/src/main/java/works/momens/server/support/openapi
```

이 위치에는 다음 구성을 둡니다.

- `OpenAPI` 기본 정보(title, version, description, server)
- Swagger UI가 사용하는 공통 component
- 실패 응답 예시를 보강하는 `OperationCustomizer`
- `@ApiException` 선언을 `ErrorCode` 목록으로 변환하는 `ApiExceptionResolver`

기능 모듈에는 해당 기능의 controller, request/response DTO, controller docs interface를 둡니다.

## 인증

보호 API는 Bearer(JWT) 인증을 사용합니다. 문서에서는 이 스킴을 `app`의 `OpenApiConfig`에서 선언하고 전역 `SecurityRequirement`로 적용합니다. 그래서 모든 operation이 기본적으로 인증을 요구하는 것으로 문서에 표시됩니다.

공개 엔드포인트(로그인, 토큰 재발급, 로그아웃 등)는 해당 `XxxControllerDocs`에서 `@SecurityRequirements`(빈 값)로 전역 요구에서 제외합니다.

Swagger의 Authorize 입력창에는 토큰 값만 넣습니다. `Bearer ` 접두사는 springdoc이 붙입니다.

## 의존성 버전 정렬

기능 모듈이 사용하는 `swagger-annotations`와 `app` 런타임의 `swagger-core`/`swagger-models`는 같은
릴리스 버전으로 맞춥니다. 일부 구성요소만 올리면 annotation 기본값을 다른 의미로 해석해 request
schema에 의도하지 않은 `default: null`이 생길 수 있습니다.

- springdoc 또는 Swagger 의존성을 올릴 때 세 구성요소의 런타임 버전을 함께 확인합니다.
- `/v3/api-docs`가 OpenAPI 3.1을 유지하고 request object schema에 의도하지 않은 `default`가 없는지
  통합 테스트로 검증합니다.
- 문서 생성 문제를 숨기기 위해 OpenAPI 3.0으로 낮추거나 직렬화된 JSON을 후처리하지 않습니다.

## Controller 문서화

공개 API controller는 Swagger annotation을 구현 로직과 분리하기 위해 `XxxControllerDocs` 인터페이스를
둘 수 있습니다.

```java
@Tag(name = "User", description = "사용자 API")
interface UserControllerDocs {

  @Operation(
      operationId = "getMe",
      summary = "내 사용자 정보 조회",
      description = "인증된 사용자 본인의 정보를 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "내 사용자 정보 조회 성공",
      content = @Content(schema = @Schema(implementation = MeResponse.class)))
  @ApiException(value = UserErrorCode.class, codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  MeResponse getMe(Principal principal);
}
```

실제 controller는 같은 signature로 docs interface를 구현합니다.

```java
@RestController
@RequiredArgsConstructor
class UserController implements UserControllerDocs {

  @Override
  @GetMapping(path = "/api/me", version = "1")
  public MeResponse getMe(Principal principal) {
    // ...
  }
}
```

path는 `/api/me` 단일 경로이며 `version = "1"` mapping을 둡니다. 레거시 path alias는 두지 않습니다.

### operationId

모든 엔드포인트의 `@Operation`에 `operationId`를 명시합니다. 값을 지정하지 않으면 springdoc이 메서드명을 기준으로 자동 생성하며, 이름이 중복되면 뒤에 `_1`을 붙입니다. 이로 인해 다른 엔드포인트를 추가할 때 기존 엔드포인트의 이름이 변경될 수 있습니다. `operationId`는 다음 규칙에 따라 정하며, 결정 배경은 [ADR-0021](../adr/0021-operation-id-naming-convention.md)에서 확인할 수 있습니다.

- `<동사><대상>` 형식의 camelCase를 사용하며, OpenAPI 문서 전체에서 고유해야 합니다.
- 목록 조회에는 `list`, 단건 조회에는 `get`, 생성에는 `create`, 수정에는 `update`, 삭제에는 `delete`를 사용합니다. 적절한 도메인 동사가 있으면 해당 동사를 우선합니다.
- 경로에 클라이언트를 구분하는 세그먼트가 있으면 해당 세그먼트를 `operationId`의 접두사로 사용합니다. `/api/mobile/**`에는 `mobile`, `/api/auth/web/**`에는 `web`, `/api/dev/**`에는 `dev`를 붙입니다. 클라이언트 구분 세그먼트가 없는 `/api/**`에는 접두사를 붙이지 않습니다.
- 접두사는 경로에서만 가져오며, 모듈명이나 클래스명에서는 가져오지 않습니다.
- 상위 자원은 문서에서 대상을 식별하는 데 필요한 범위까지만 이름에 포함합니다.

예를 들어 `PATCH /api/tasks/{taskId}`의 `operationId`는 `updateTask`, `PATCH /api/mobile/tasks/{taskId}`의 `operationId`는 `mobileUpdateTask`로 지정합니다.

`OpenApiOperationIdTest`는 모든 `@Operation`에 `operationId`가 명시되어 있는지와 각 값이 형식 및 접두사 규칙을 준수하는지 검사합니다. 또한 커밋된 스냅샷의 모든 `operationId`가 애너테이션에 명시된 값과 일치하는지도 대조합니다.

## 성공 응답

성공 응답은 실제 response DTO shape 그대로 문서화합니다.

- 단일 리소스 응답은 해당 response DTO를 `@ApiResponse`의 `schema`로 명시합니다.
- body가 없는 성공은 `204 No Content`로 명시합니다.
- 전역 성공 wrapper(`success`, `data`, `CommonApiResponse`)는 사용하지 않습니다.

```java
@ApiResponse(
    responseCode = "200",
    description = "내 사용자 정보 조회 성공",
    content = @Content(schema = @Schema(implementation = MeResponse.class)))
```

## 실패 응답

실패 응답 예시는 `@ApiException`과 `OperationCustomizer`를 사용해 자동으로 생성합니다.

- `@ApiException`의 `value`에는 문서화할 `ErrorCode` enum 클래스를 지정하고, `codes`에는 해당 enum에서
  문서화할 에러 코드를 지정합니다. 같은 메서드에 여러 번 선언할 수 있습니다.
- `OperationCustomizer`는 `ApiExceptionResolver`가 해석한 에러 코드를 바탕으로 HTTP 상태 코드별 OpenAPI
  응답과 예시를 추가합니다.
- `codes`에는 해당 엔드포인트에서 실제로 반환할 수 있는 도메인 에러 코드만 지정합니다. 반환할 수 없는
  코드가 문서에 포함되면 클라이언트에 불필요한 예외 처리 분기가 생깁니다.
- `codes`를 비워 두면 해당 enum의 모든 상수를 문서화합니다. 공통 에러 코드처럼 엔드포인트별 범위가 아직
  정해지지 않은 경우에만 사용하는 과도기적 표현이며, 범위가 정해지면 `codes`를 명시합니다.
- `codes`에 지정한 이름은 컴파일러가 검증하지 않습니다. `ApiExceptionDeclarationTest`에서 해당 이름이
  enum에 존재하는지 확인합니다.
- Standard 모드에서는 아래 형식을 사용합니다.

```json
{
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "사용자를 찾을 수 없습니다."
  }
}
```

`details`가 필요한 에러 예시는 `details`를 포함할 수 있습니다. 민감 정보, 내부 예외 메시지, SQL,
stack trace는 예시에 넣지 않습니다.

Legacy compatible 모드 엔드포인트는 기존 Go API의 실패 body shape를 유지해 예시를 작성합니다.

```json
{
  "error": "unauthorized"
}
```

## DTO Schema

Request/response DTO는 `record`를 기본으로 하고 `@Schema`로 문서화합니다.

- record 자체에 설명을 둡니다.
- 주요 필드에는 `description`, `example`, `nullable`을 명시합니다.
- validation annotation과 `@Schema` 설명이 서로 어긋나지 않게 합니다.

### Property 이름(snake_case)

wire format은 snake_case이고, DTO는 런타임 직렬화에 Jackson 3 `@JsonNaming(SnakeCaseStrategy)`를
씁니다. 스키마를 만드는 swagger-core는 Jackson 2라 이 애노테이션을 읽지 못해, 그대로 두면 스키마
property가 Java 필드명(camelCase)으로 나가 실제 API와 어긋납니다.

- `app`이 등록하는 `ModelResolver` 빈이 swagger-core의 Jackson 2 ObjectMapper에 snake_case 전략을
  걸어 스키마 property를 전역 snake_case로 생성합니다. DTO에는 별도 애노테이션을 추가하지 않습니다.
- 특정 필드만 다른 이름이 필요하면 Jackson 2 `@JsonProperty`로 override합니다.
- 모든 스키마 property가 snake_case인지 통합 테스트로 검증합니다.

## API Version Header

모든 엔드포인트는 [API 버저닝](api-versioning.md)이 적용되므로 `API-Version` request header를 문서화합니다.
초기 버전은 `1`입니다.

springdoc이 Spring MVC API versioning 설정과 handler의 `version` mapping을 읽어 문자열 header schema를
자동 생성합니다. `@Operation.parameters`에 `API-Version`을 다시 선언하면 자동 생성된 parameter와 중복되므로
직접 선언하지 않습니다.

## 스냅샷 고정

`/v3/api-docs` 결과를 `docs/spec/openapi.json`으로 커밋합니다. 소비자(`momens-fe`)는 이 파일에서
타입을 생성하므로, 계약 변경이 PR diff에 드러나야 합니다.

`OpenApiSnapshotTest`가 커밋본과 실제 문서를 대조하고 다르면 실패합니다. CI는 `test`를 돌리므로
스냅샷을 갱신하지 않으면 계약 변경이 머지되지 않습니다.

갱신은 명시적인 행위입니다. 테스트는 비교만 하고, 파일을 쓰는 것은 갱신 task 하나뿐입니다.

```bash
./gradlew updateOpenApiSnapshot
```

갱신 후에는 `git diff docs/spec/openapi.json`으로 바뀐 계약이 의도한 것인지 확인하고 PR에 포함합니다.

계약을 건드리는 PR이 동시에 열려 있으면 스냅샷에서 충돌이 납니다. JSON을 손으로 풀지 말고 rebase 후
갱신 task를 다시 돌립니다.

```bash
git rebase origin/develop
./gradlew updateOpenApiSnapshot
```

스냅샷은 정규화해 직렬화합니다. springdoc 출력의 path·schema 순서가 실행 간 안정적이라고 가정하지
않고 객체 키를 정렬하며, 줄바꿈은 `\n`으로 고정합니다. 생성과 비교가 같은 정규화 함수를 공유해 두 경로가
어긋나지 않습니다.

정렬 대상은 객체 키와 루트 `tags` 배열뿐입니다. 배열을 일괄 정렬하지는 않습니다. `enum`, `required`,
`parameters`와 예시 값 배열은 순서가 내용의 일부이거나 선언 순서를 따라 안정적이라, 정렬하면 의미를
바꾸거나 얻는 것 없이 diff만 키웁니다. 루트 `tags`만 예외인 이유는 springdoc이 set 순회 순서로 내보내,
tag를 하나 추가하면 배열 전체가 재정렬되어 무관한 churn diff가 생기고 springdoc·JDK 업그레이드가 계약
변경 없이 게이트를 깨뜨리기 때문입니다.

환경마다 달라지는 값은 스냅샷에서 제외합니다. `servers`는 `MOMENS_OPENAPI_SERVER_URL`로 주입되므로
그대로 두면 실행 환경이 스냅샷을 바꿉니다.

## 구현 체크리스트

- controller docs interface를 둘지 먼저 결정합니다.
- 성공 응답은 실제 DTO shape 그대로 문서화합니다.
- 주요 실패 응답은 `@ApiException`으로 선언합니다.
- 해당 엔드포인트에서 실제로 반환하는 도메인 에러 코드를 `codes`에 빠짐없이 지정했는지 리뷰에서
  확인합니다.
- 모든 엔드포인트에 `operationId`를 명시하고 [operationId](#operationid) 절의 명명 규칙을 따릅니다.
- 모든 엔드포인트는 `API-Version` header를 문서화합니다.
- controller docs에 `API-Version` parameter를 직접 중복 선언하지 않습니다.
- 모든 path를 `/api` prefix 단일 경로로 문서화하고, 레거시 alias path를 두지 않습니다.
- 계약을 바꿨으면 `./gradlew updateOpenApiSnapshot`으로 스냅샷을 갱신하고 diff를 PR에 포함합니다.
