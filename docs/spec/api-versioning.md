# API 버저닝

이 문서는 `momens-server` HTTP JSON API의 버저닝 원칙을 정의합니다.
`momens-retrieval`의 gRPC 계약, `momens-worker`의 내부 API, 외부 webhook payload는 이 문서의
범위가 아닙니다. 이 정책의 배경과 결정 맥락은 [ADR-0006](../adr/0006-api-path-and-versioning-policy.md)에 있습니다.

## 원칙

- API 버저닝은 Spring Boot 4 / Spring Framework 7의 API versioning 기능을 사용합니다.
- 버전별 핸들러 분기는 Spring MVC request mapping의 `version` 속성으로 표현합니다.
- 클라이언트는 request header `API-Version`으로 요청 API 버전을 전달합니다.
- Momens 제품 API path는 `/api` prefix를 사용합니다. 레거시 path alias(`/me` 등)는 두지 않습니다.
- 버저닝은 레거시 이관 엔드포인트를 포함한 모든 제품 API에 적용하며, 초기 버전은 `1`입니다.
- 외부 표준이 자체 버전 협상·well-known 경로를 정의하는 MCP/OAuth protocol endpoint는 아래 예외 규칙을
  따릅니다.

## 적용 대상

버저닝은 **모든 Momens 제품 API**에 적용합니다. 레거시 Go API를 이관한 엔드포인트도 예외가 아닙니다.

- 새로 추가하는 공개 API
- 기존 API를 호환되지 않게 바꾸는 개편 API
- 레거시 Go API를 이관한 엔드포인트

레거시 이관 엔드포인트도 `/api` prefix와 `version = "1"` mapping을 갖추고, 클라이언트는 `API-Version`
헤더를 전달합니다. 기존 Go API의 path(`/me` 등)를 alias로 유지하지 않습니다. 레거시 클라이언트는 새
서버로 호출을 옮길 때 `/api` prefix와 `API-Version` 헤더를 사용하도록 맞춥니다.

응답 body shape는 별도 정책입니다. 레거시 이관 엔드포인트는 [API 응답과 에러 코드](api-response-error-codes.md)의
Legacy compatible 응답 모드를 유지할 수 있습니다. path·버저닝 일원화와 응답 body 호환은 독립적으로 다룹니다.

API 명세서를 전달할 때는 제품 API가 `/api` prefix와 `API-Version` 헤더를 쓴다는 사실을 안내합니다.

### 외부 표준 protocol 예외

MCP/OAuth client와 표준 discovery 호환성을 위해 다음 endpoint에는 Spring MVC `version` mapping과
`API-Version` 헤더를 적용하지 않습니다.

- MCP Streamable HTTP `/api/mcp`: `MCP-Protocol-Version` 헤더와 요청 metadata로 MCP 버전을 협상합니다.
- OAuth protocol endpoint `/api/oauth2/**`: Spring Authorization Server가 처리하며 요청·응답 형식은
  OAuth 표준(RFC 6749, RFC 7591 등)을 따릅니다.
- `/.well-known/**`: 표준이 정한 discovery 경로이므로 `/api` prefix와 `API-Version`을 사용하지 않습니다.

이 예외는 `:mcp`가 소유하는 외부 표준 protocol 표면에만 적용합니다. 같은 모듈 안에서도 Momens 관리용
제품 API를 추가하면 일반 규칙대로 `/api`와 `API-Version`을 사용합니다. MCP canonical resource는
[ADR-0023](../adr/0023-mcp-oauth-migration-boundary-and-cutover.md)에 따라
`https://api.momens.works/api/mcp`로 고정합니다.

## 버전 전달 방식

버전은 `API-Version` request header로 전달합니다.

```http
GET /api/posts/1 HTTP/1.1
API-Version: 1
```

URL에는 리소스 path를 유지하고, 버전 선택은 Spring API versioning resolver가 담당합니다.

## Controller 작성 방식

버전별 핸들러는 request mapping의 `version` 속성으로 구분합니다.

```java
@RestController
@RequestMapping("/api/posts")
public class PostController {

  @GetMapping(path = "/{id}", version = "1")
  public PostV1Response getPostV1(@PathVariable Long id) {
    return new PostV1Response(id, "title");
  }

  @GetMapping(path = "/{id}", version = "2")
  public PostV2Response getPostV2(@PathVariable Long id) {
    return new PostV2Response(id, "title", "author");
  }
}
```

제품 API controller path는 항상 `/api` prefix를 사용하고, 모든 handler는 `version` 속성을 갖습니다(초기
버전 `1`). 레거시 path를 alias로 추가하지 않습니다. MCP/OAuth protocol controller는 위 예외 규칙을
따릅니다.

## Breaking change 기준

다음은 breaking change입니다.

- path, HTTP method, 필수 query/path parameter 변경
- request body 필드 삭제, 이름 변경, 타입 변경, 필수 필드 추가
- response body 필드 삭제, 이름 변경, 타입 변경
- 성공 HTTP status 변경
- 클라이언트 분기 대상인 에러 code 삭제 또는 의미 변경
- 기존 nullable 필드를 non-null로 바꾸거나, 기존 optional 필드를 required로 바꾸는 변경

다음은 일반적으로 breaking change로 보지 않습니다.

- response body에 optional 필드 추가
- 새 에러 code 추가
- OpenAPI 설명, 예시, summary 수정
- 서버 내부 구현, DB schema, Java package 변경

## 구현 체크리스트

- 모든 제품 API path에 `/api` prefix를 사용합니다. 레거시 path alias는 두지 않습니다.
- 모든 제품 API handler에 `version` mapping을 둡니다(초기 버전 `1`).
- 제품 API 클라이언트는 `API-Version` header를 전달합니다.
- MCP/OAuth protocol과 well-known handler에는 `API-Version`을 추가하지 않고 각 표준의 버전 협상 규칙을
  사용합니다.
- OpenAPI의 `API-Version` header는 Spring MVC API versioning 설정에서 자동 생성하며 controller docs에
  중복 선언하지 않습니다([OpenAPI 규칙](openapi.md#api-version-header)).
- 레거시 이관 엔드포인트의 응답 body 호환 여부는 응답 문서의 모드를 따릅니다.
- API 명세서를 전달할 때 제품 API와 외부 표준 protocol의 버전 전달 방식이 다름을 안내합니다.
