# API 응답과 에러 코드

이 문서는 `momens-server`의 HTTP JSON API 응답 규격을 정의합니다.
`momens-retrieval`의 gRPC 계약, `momens-worker`의 내부 처리 결과, 외부 webhook payload는
이 문서의 범위가 아닙니다.

## 원칙

- 기존 `momens-api`에서 이관하는 엔드포인트는 **HTTP status와 JSON body shape를 우선 보존**합니다.
- 성공 응답에는 전역 wrapper(`success`, `data`)를 두지 않습니다.
- Standard 모드에서 클라이언트는 HTTP status와 안정적인 error `code`로 분기합니다.
  `message` 문자열을 파싱하지 않습니다.
- 서버 내부 예외 메시지, SQL, stack trace, secret, 개인정보는 응답에 노출하지 않습니다.
- 각 엔드포인트는 OpenAPI에 성공/실패 예시를 함께 둡니다.

## 응답 모드

마이그레이션 기간에는 두 가지 응답 모드가 공존할 수 있습니다.

| 모드 | 사용 대상 | 실패 body |
| --- | --- | --- |
| Legacy compatible | 기존 Go API를 그대로 이관하는 엔드포인트 | `{ "error": "..." }` |
| Standard | 신규 엔드포인트 또는 계약 변경이 합의된 개편 엔드포인트 | `{ "error": { "code": "...", "message": "...", "details": { ... } } }` |

엔드포인트를 구현할 때는 어떤 모드인지 먼저 정하고, OpenAPI 예시에 명시합니다.
기존 엔드포인트의 모드를 Standard로 바꿀 때는 프론트엔드와 별도 합의가 필요합니다.

## 성공 응답

전역 wrapper는 사용하지 않습니다.

### 단일 리소스

레거시가 리소스를 직접 반환했다면 직접 반환합니다.

```json
{
  "id": "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8",
  "name": "Momens"
}
```

레거시가 이름 있는 wrapper를 사용했다면 같은 shape를 유지합니다.

```json
{
  "user": {
    "id": "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8",
    "email": "user@example.com"
  }
}
```

### 목록

목록은 컬렉션 이름으로 감싼 객체를 기본으로 합니다. 빈 목록은 `null`이 아니라 `[]`입니다.

```json
{
  "workspaces": []
}
```

### 명령 성공

반환할 리소스가 없는 성공 응답은 기존 Go API와 맞춰 `{ "message": "..." }`를 사용합니다.

```json
{
  "message": "updated"
}
```

새 엔드포인트에서 body가 정말 필요 없는 경우에만 `204 No Content`를 고려합니다. 기존 엔드포인트를
이관할 때는 레거시 응답을 우선합니다.

## 표준 에러 응답

Standard 모드의 에러 응답은 아래 형태를 사용합니다.

```json
{
  "error": {
    "code": "WORKSPACE_NOT_FOUND",
    "message": "워크스페이스를 찾을 수 없습니다.",
    "details": {
      "workspace_id": "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8"
    }
  }
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `error.code` | 예 | 클라이언트 분기용 안정 코드 |
| `error.message` | 예 | 사람에게 보여줄 수 있는 설명. 클라이언트가 파싱하지 않음 |
| `error.details` | 아니오 | 필드 오류, 리소스 식별자 등 부가 정보. 민감 정보 금지 |

`details`가 없으면 필드 자체를 생략합니다.

## Legacy compatible 에러 응답

기존 Go API를 그대로 이관하는 엔드포인트는 아래 형태를 유지합니다.

```json
{
  "error": "unauthorized"
}
```

이 모드에서도 서버 내부에서는 표준 에러 코드를 매핑할 수 있습니다. 단, public body에 `code`를
추가하는 것은 계약 변경으로 보고 별도 합의 후 진행합니다.

## 에러 코드 네이밍

에러 코드는 `DOMAIN_REASON` 형식의 `UPPER_SNAKE_CASE`를 사용합니다.

- 공통 에러는 `COMMON_` prefix를 사용합니다.
- 인증/인가 에러는 `AUTH_` prefix를 사용합니다.
- 도메인 에러는 도메인 이름을 prefix로 사용합니다. 예: `WORKSPACE_`, `MEMORY_`, `SOURCE_`.
- 같은 의미의 에러 코드는 HTTP status가 달라도 재사용하지 않습니다.
- 코드는 삭제하거나 의미를 바꾸지 않습니다. 필요하면 새 코드를 추가합니다.

## 공통 에러 코드

| HTTP status | Code | 사용 기준 |
| --- | --- | --- |
| 400 | `COMMON_BAD_REQUEST` | 요청 형식, 타입, JSON 파싱 오류 |
| 400 | `COMMON_VALIDATION_FAILED` | Bean Validation 등 필드 검증 실패 |
| 401 | `AUTH_UNAUTHORIZED` | 인증 정보 없음 |
| 401 | `AUTH_INVALID_TOKEN` | 토큰 파싱/검증 실패 |
| 403 | `AUTH_FORBIDDEN` | 인증은 되었지만 권한 없음 |
| 404 | `COMMON_NOT_FOUND` | 특정 도메인 코드가 아직 없을 때의 기본 not found |
| 405 | `COMMON_METHOD_NOT_ALLOWED` | 해당 경로에서 허용되지 않은 HTTP 메서드 |
| 409 | `COMMON_CONFLICT` | 중복, 상태 충돌 등 기본 conflict |
| 415 | `COMMON_UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 요청 Content-Type |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 서버 내부 오류. 상세 원인 노출 금지 |
| 502 | `COMMON_BAD_GATEWAY` | 외부 서비스 호출 실패 |

도메인별 에러가 필요하면 공통 코드를 그대로 쓰기보다 의미가 드러나는 도메인 코드를 추가합니다.

예:

| HTTP status | Code | 사용 기준 |
| --- | --- | --- |
| 400 | `WORKSPACE_INVALID_SLUG` | workspace slug 형식이 유효하지 않음 |
| 400 | `WORKSPACE_RESERVED_SLUG` | 예약어로 지정되어 사용할 수 없는 workspace slug |
| 409 | `WORKSPACE_SLUG_ALREADY_EXISTS` | workspace slug 중복 |
| 404 | `WORKSPACE_NOT_FOUND` | workspace를 찾을 수 없음 |
| 400 | `INVITATION_INVALID_TOKEN` | 초대 토큰 형식이 유효하지 않음 |
| 404 | `INVITATION_NOT_FOUND` | 초대를 찾을 수 없음 |
| 409 | `INVITATION_EXPIRED` | 초대가 만료됨 |

### 구현된 도메인 코드

| HTTP status | Code | 사용 기준 |
| --- | --- | --- |
| 401 | `AUTH_GOOGLE_TOKEN_INVALID` | Google ID 토큰 검증 실패 |
| 401 | `AUTH_GOOGLE_EMAIL_NOT_VERIFIED` | Google 계정 이메일이 검증되지 않음 |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | refresh token 형식·해시·만료·폐기 상태가 유효하지 않음 |
| 400 | `AUTH_OAUTH_STATE_INVALID` | 웹 OAuth 콜백의 state 불일치·누락 또는 code 누락 |
| 502 | `AUTH_OAUTH_EXCHANGE_FAILED` | 웹 OAuth code 교환·userinfo 조회 실패 |
| 401 | `AUTH_DEV_TOKEN_SECRET_INVALID` | dev 토큰 발급 요청에서 공유 시크릿 헤더가 없거나 일치하지 않음 (dev 계열 프로필 전용) |
| 403 | `AUTH_DEV_TOKEN_EMAIL_NOT_ALLOWED` | dev 토큰 발급 대상 이메일이 allowlist에 없음 (dev 계열 프로필 전용) |
| 404 | `USER_NOT_FOUND` | 사용자를 찾을 수 없음 (`GET/PATCH /api/me` 등) |
| 409 | `USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY` | 이메일로 조회한 사용자에게 이미 다른 로그인 수단이 연결되어 있어 Google 계정을 연결할 수 없음 |
| 409 | `SIGNAL_INVALID_STATE` | 이미 다른 action으로 처리된 Signal에 다른 action을 요청함(같은 action 재요청은 200 멱등 응답) |
| 404 | `WORKSPACE_MEMBER_NOT_FOUND` | 대상 사용자가 해당 workspace의 멤버가 아님 |
| 400 | `WORKSPACE_INVALID_ROLE` | 부여할 수 없는 역할. `admin`과 `member`만 허용하며, `owner`와 정의되지 않은 값이 해당함 |
| 409 | `WORKSPACE_OWNER_PROTECTED` | 대상 사용자가 owner이므로 역할을 변경하거나 멤버십을 제거할 수 없음 |
| 409 | `WORKSPACE_SELF_REMOVAL_NOT_ALLOWED` | 요청자가 자기 자신을 workspace에서 제거하려 함 |
| 400 | `INVITATION_INVALID_EMAIL` | 초대할 이메일 형식이 올바르지 않음 |
| 400 | `INVITATION_INVALID_TOKEN` | 초대 토큰이 비어 있거나 알 수 없는 상태의 초대를 가리킴 |
| 403 | `INVITATION_EMAIL_MISMATCH` | 초대받은 이메일과 로그인한 계정의 이메일이 일치하지 않음 |
| 404 | `INVITATION_NOT_FOUND` | 초대를 찾을 수 없음 |
| 409 | `INVITATION_ALREADY_ACCEPTED` | 이미 수락된 초대이므로 재발송, 폐기, 재수락할 수 없음 |
| 409 | `INVITATION_EXPIRED` | 만료된 초대를 수락하려 함 |
| 409 | `INVITATION_REVOKED` | 폐기된 초대를 수락하려 함 |
| 502 | `INVITATION_EMAIL_SEND_FAILED` | 초대 이메일 발송에 실패함 |
| 409 | `WORKSPACE_MEMBER_ALREADY_EXISTS` | 초대 대상이 이미 워크스페이스 멤버임 |
| 403 | `WORKSPACE_INVITEE_NOT_FOUND` | 멤버로 추가할 이메일에 해당하는 사용자가 없음 |
| 403 | `WORKSPACE_MEMBER_ROLE_CONFLICT` | 대상 사용자가 이미 다른 역할로 워크스페이스에 참여 중임 |

`INVITATION_NOT_FOUND`, `INVITATION_INVALID_TOKEN`, `INVITATION_EXPIRED`는 예시 표에 정의되어 있던
코드를 구현한 것이다. `WORKSPACE_INVITEE_NOT_FOUND`와 `WORKSPACE_MEMBER_ROLE_CONFLICT`를 403으로
정의한 것은 레거시가 이 경로의 실패를 모두 403으로 응답하기 때문이다. 기존 HTTP status를 보존한다는
이관 원칙을 따른 결과이다.

### 모바일 MVP 예정 도메인 코드

모바일 MVP API를 구현할 때는 다음 도메인 코드를 추가한다.

| HTTP status | Code | 사용 기준 |
| --- | --- | --- |
| 404 | `PROJECT_NOT_FOUND` | 프로젝트를 찾을 수 없음 |
| 404 | `SIGNAL_NOT_FOUND` | Signal을 찾을 수 없음 |
| 404 | `TASK_NOT_FOUND` | 태스크를 찾을 수 없음 |
| 404 | `TASK_CHECKLIST_ITEM_NOT_FOUND` | 태스크 체크리스트 항목을 찾을 수 없음 |

웹 이관에서는 다음 도메인 코드를 추가한다.

| HTTP status | Code | 사용 기준 |
| --- | --- | --- |
| 400 | `CONTEXT_CROSS_WORKSPACE_LINK_NOT_ALLOWED` | 연결할 두 대상이 서로 다른 워크스페이스에 속함 |
| 404 | `CONTEXT_LINK_NOT_FOUND` | 해제할 연결이 없음 |

### 웹 Google 로그인(Authorization Code) 리다이렉트 계약

`GET /api/auth/google/login`·`GET /api/auth/google/callback`은 브라우저 리다이렉트(302)로 동작하므로
JSON 에러 본문을 쓰지 않습니다. 콜백 실패는 브라우저에 JSON을 노출하지 않도록 `failure-uri`로
리다이렉트하며 `?error=`에 다음 값 중 하나를 싣습니다(민감정보 미포함).

| `?error=` | 의미 | 매핑 에러 코드 |
| --- | --- | --- |
| `invalid_state` | state 불일치·누락 또는 code 누락 | `AUTH_OAUTH_STATE_INVALID` |
| `email_not_verified` | Google 이메일 미검증 | `AUTH_GOOGLE_EMAIL_NOT_VERIFIED` |
| `email_conflict` | 이메일로 조회한 사용자에게 이미 다른 로그인 수단이 연결되어 있음 | `USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY` |
| `google_error` | code 교환·userinfo 실패 | `AUTH_OAUTH_EXCHANGE_FAILED` |
| `server_error` | 그 외 예기치 못한 오류 | (기타) |

성공 시 access/refresh를 HttpOnly 쿠키로 설정하고 `success-uri`로 리다이렉트합니다.

### 웹 쿠키 세션 갱신·로그아웃 계약

웹은 access 만료 시 refresh 쿠키로 회전 재발급하고 로그아웃 시 세션 쿠키를 정리합니다. 두 경로 모두
`/api/auth` 하위라 refresh 쿠키(`Path=/api/auth`)가 전송되며, same-domain 배포라 CSRF는 SameSite로
충족합니다(별도 토큰 없음). 토큰이 HttpOnly 쿠키에 있어 본문은 없습니다.

| 메서드·경로 | 성공 | 실패 |
| --- | --- | --- |
| `POST /api/auth/web/refresh` | `204` + `Set-Cookie`로 access/refresh 회전 | refresh 쿠키 없음·무효 → `401` `AUTH_REFRESH_TOKEN_INVALID` |
| `POST /api/auth/web/logout` | `204` + access/refresh/legacy session 쿠키 정리(`Max-Age=0`) | 없음(쿠키 유무·상태와 무관하게 `204`, 멱등) |

모바일 JSON 계약(`POST /api/auth/refresh`·`/api/auth/logout`, body의 `refresh_token`)은 그대로
유지됩니다.

## Validation details

Bean Validation과 `FieldValidationException`을 통한 필드 단위 검증 실패는 `details.fields`에
정리합니다. 각 원소의 `field`는 JSON 요청의 `snake_case` 필드명을 사용하고, `reason`은 사람이
문제를 이해하기 위한 설명으로 제공합니다. 클라이언트는 `reason` 문구나 배열 순서를 분기 기준으로
사용하지 않습니다.

```json
{
  "error": {
    "code": "COMMON_VALIDATION_FAILED",
    "message": "요청 값이 유효하지 않습니다.",
    "details": {
      "fields": [
        {
          "field": "email",
          "reason": "must be a well-formed email address"
        }
      ]
    }
  }
}
```

## 권한 details

권한이 부족해 `AUTH_FORBIDDEN`을 반환할 때는 `details.required_role`에 요청을 처리하는 데 필요한 최소 역할을 포함합니다.

```json
{
  "error": {
    "code": "AUTH_FORBIDDEN",
    "message": "권한이 없습니다.",
    "details": {
      "workspace_id": "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8",
      "required_role": "admin"
    }
  }
}
```

`required_role`에는 `workspace_members.role`에 저장하는 소문자 문자열을 사용하며, 값은 `owner`, `admin`, `member` 중 하나입니다. 역할에는 서열이 있으며, `required_role`은 해당 요청을 처리하는 데 필요한 최소 역할을 의미합니다. 예를 들어 값이 `admin`이면 `admin`과 `owner` 역할이 요청을 처리할 수 있습니다.

멤버가 아닌 경우와 멤버이지만 역할이 부족한 경우는 모두 같은 에러 코드로 응답합니다. 두 경우를 별도 코드로 구분하면 클라이언트가 워크스페이스 멤버십 여부를 추론할 수 있으므로 구분하지 않습니다.

## HTTP status 사용 기준

- `200 OK`: 조회, 수정, 명령 성공 응답.
- `201 Created`: 리소스 생성 성공.
- `204 No Content`: 신규 엔드포인트에서 반환 body가 없기로 명시한 경우만 사용.
- `400 Bad Request`: 요청 형식/검증/도메인 입력 오류.
- `401 Unauthorized`: 인증 실패.
- `403 Forbidden`: 권한 없음.
- `404 Not Found`: 리소스 없음.
- `409 Conflict`: 이미 존재함, 상태 전이 불가, 만료/이미 처리됨 등 충돌.
- `500 Internal Server Error`: 서버 내부 오류.
- `502 Bad Gateway`: 외부 API 또는 downstream 서비스 호출 실패.

## 구현 시 체크리스트

- 기존 Go API 이관인지, 신규/개편 API인지 먼저 결정합니다.
- 기존 Go API 이관이면 status와 body shape를 기존과 맞춥니다.
- 신규/개편 API이면 Standard 에러 응답을 사용합니다.
- 새 에러 코드를 추가하면 이 문서의 코드 표 또는 도메인별 명세에 반영합니다.
- OpenAPI에 성공 예시와 주요 에러 예시를 함께 추가합니다.
