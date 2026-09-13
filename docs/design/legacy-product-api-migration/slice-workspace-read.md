# 첫 웹 read 수직 슬라이스 계약 (워크스페이스 조회)

상태: 구현 완료 (계약 확정)

작성일: 2026-08-17

레거시 기준선: `Momens-Works/momens-api@71bbd07614fd2aef4dec726bafdf86c1bd097ba6`

관련 작업: `MOM-0850`(계약 확정), `MOM-0851`(구현)

관련 문서: [이관 전략](strategy.md),
[이관 원장](ledger.md),
[API 버저닝](../../spec/api-versioning.md),
[API 응답과 에러 코드](../../spec/api-response-error-codes.md)

## 1. 목적

[이관 원장](ledger.md)이 비교만 해둔 read-only 후보 중 첫 수직
슬라이스를 확정하고, 구현 전에 잠가야 할 계약을 고정한다. 이 문서가 확정한 뒤에야 구현 작업을
만든다.

## 2. 슬라이스 선정

첫 슬라이스는 **워크스페이스 조회(H020, H022)** 로 확정한다.

| 후보 | 판단 근거 |
| --- | --- |
| **워크스페이스 목록·상세 (H020, H022)** | **확정.** 레거시 응답 필드가 target 엔티티와 1:1로 일치하고, 계산 필드·조인·projection이 없다 |
| 프로젝트 목록·상세 (H038, H047) | 보류. 레거시 `label`, `owner_user_ids`, `health_status`, `progress`, `unresolved_count`, `voc_signal_count`, `last_context_at`, `metadata`가 target 엔티티에 없어 파생값 계산·보관 정책을 첫 슬라이스에서 함께 정해야 한다 |
| 마일스톤·태스크·결정/블로커 read | 보류. 원장의 판단을 유지한다 |

확정 근거는 다음 대조에서 나온다.

| 필드 | 레거시 `domain.Workspace` | target `Workspace` |
| --- | --- | --- |
| `id` | `uuid.UUID` | `BaseEntity.id` |
| `name` | `string` | `name` |
| `slug` | `string` | `slug` (unique) |
| `description` | `*string` (`omitempty`) | `description` (nullable) |
| `created_at` | `time.Time` | `BaseEntity.createdAt` |
| `updated_at` | `time.Time` | `BaseEntity.updatedAt` |

`workspaces` 테이블 Flyway는 이미 `:workspace` 모듈이 소유한다
(`V20260627090000__create_workspace.sql`). **이 슬라이스에 신규 DDL은 없다.**

또한 `MOM-0845`(모바일 워크스페이스 scope)는 이 슬라이스의 선행 조건이 아니다. 대상이 웹 계약이고
모바일 bootstrap 응답을 건드리지 않는다.

## 3. 레거시 트레이스

기준선 SHA에서 확인한 사실만 적는다.

| 단계 | 위치 | 확인 내용 |
| --- | --- | --- |
| route | `internal/bootstrap/router.go:93` | `r.Group("/workspaces")` + `Use(requireAuth)`. `GET ""` → `List`, `GET "/:id"` → `Get` |
| middleware | `internal/platform/httpx/middleware.go:72` | `session_token` 쿠키의 HS256 JWT. subject = `users.id`. 쿠키 없음 401 `{"error":"unauthorized"}`, 파싱 실패 401 `{"error":"invalid token"}` |
| handler | `internal/workspace/handler.go:93` | List: `{"workspaces": [...]}`, nil → `[]` 보장 |
| handler | `internal/workspace/handler.go:111` | Get: 래퍼 없는 단일 객체. path id 파싱 실패 400. **그 밖의 모든 실패를 403으로 반환** |
| service | `internal/workspace/service.go:166` | List는 권한 검사 없이 멤버십 조인 결과를 그대로 반환 |
| service | `internal/workspace/service.go:170` | Get은 `RequireMember` 통과 후 `GetByID` |
| repository | `internal/workspace/repository.go:59` | `workspaces JOIN workspace_members ON user_id = $1`, `ORDER BY w.created_at DESC` |
| repository | `internal/workspace/repository.go:47` | `SELECT ... FROM workspaces WHERE id = $1`. **soft-delete 컬럼·필터 없음** |
| schema | `migrations/000001_init.sql` | `workspaces`, `workspace_members` |
| test | `internal/workspace/` | **List·Get characterization 테스트 없음.** 기존 테스트는 slug 수명주기, seed, onboarding, invitation |

레거시 전용 테스트가 없으므로 **이 문서가 계약의 출처**이고, 검증 오라클은 `../e2e` 대조 스위트다.

## 4. 잠근 계약

### 4.1 path와 버저닝

[API 버저닝](../../spec/api-versioning.md)과 ADR-0006을 그대로 따른다. 레거시 path alias는 두지 않는다.

| 레거시 | 신규 |
| --- | --- |
| `GET /workspaces` | `GET /api/workspaces` (`version = "1"`) |
| `GET /workspaces/:id` | `GET /api/workspaces/{workspaceId}` (`version = "1"`) |

### 4.2 인증

보호 체인이 access token을 읽고, 컨트롤러는 `CurrentUser.id(principal)`로 `users.id`를 받는다.
레거시와 신규 토큰 모두 HS256이고 subject가 `users.id`이며 DB를 공유하므로, 사용자 식별은 추가
매핑 없이 일치한다.

전환기에는 레거시 `session_token` 쿠키를 함께 수용한다([ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md)).
운영 웹이 레거시 OAuth로 로그인해 `access_token` 쿠키를 갖지 않으므로, 수용하지 않으면 전환 즉시
`401`이 된다. `BearerTokenResolver`의 조회 순서는 다음과 같다.

1. `Authorization` 헤더
2. `access_token` 쿠키
3. `session_token` 쿠키 (전환기 한시)

디코더와 서명 키는 바꾸지 않는다. 웹 로그인이 신규 서버로 전환되면 3번을 제거한다.

레거시 서버가 신규 access token을 읽게 하는 패치(이관 전략 결정②A)는 방향이 반대이고 이
슬라이스의 선행 조건이 아니다.

### 4.3 응답 body

성공 응답은 레거시 shape를 유지한다. 전역 wrapper는 쓰지 않는다.

`GET /api/workspaces` — 200

```json
{
  "workspaces": [
    {
      "id": "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8",
      "name": "Momens",
      "slug": "momens",
      "description": "제품팀 워크스페이스",
      "created_at": "2026-06-27T09:00:00Z",
      "updated_at": "2026-06-27T09:00:00Z"
    }
  ]
}
```

- 빈 결과는 `null`이 아니라 `[]`다.
- 정렬은 `created_at` 내림차순이다.
- 요청자가 멤버인 워크스페이스만 포함한다. 별도 권한 검사 없이 멤버십 조인이 곧 필터다.

`GET /api/workspaces/{workspaceId}` — 200: 위 배열 원소와 같은 객체를 래퍼 없이 반환한다.

`description`은 값이 없으면 필드를 생략한다(레거시 `omitempty` 동작 유지).

soft-delete 필터는 두지 않는다. 레거시에 해당 컬럼과 필터가 없어 재현할 동작이 없다.

### 4.4 에러 응답 — 확정 (FE 합의 완료)

> FE 합의가 완료되어 아래 잠정안이 그대로 확정됐다(`MOM-0851`). 이후 이 합의는 H020·H022를 넘어
> **웹 이관 endpoint 전면**으로 확대됐고, 이관 원장의 공통 전환 규칙도 에러 응답 전면 Standard로
> 갱신됐다(2026-08-18, `MOM-0856`). 따라서 이 절은 더 이상 예외 조항이 아니라 공통 규칙의 첫 적용
> 사례다.

**Standard 모드**로 확정한다.

전환에 클라이언트가 path를 바꾸고 `API-Version` 헤더를 붙이는 배포가 필요하므로, 레거시 body를
보존해서 얻는 호환 이득이 없다. 또한 레거시 Get은 미존재와 권한 없음을 구분하지 않고 Go의 raw error
문자열을 그대로 실어 보내, 내부 예외 메시지를 노출하지 않는다는 응답 규격과 충돌한다.

401·403은 보호 체인의 `RestAuthenticationEntryPoint`·`RestAccessDeniedHandler`가 이미 Standard
형식으로 내보낸다. `Legacy compatible`로 되돌릴 경우 이 두 endpoint만 필터 체인 응답을 따로
처리해야 하므로, 되돌리는 쪽이 구현이 더 크다는 점을 합의 자리에서 함께 다룬다.

| 상황 | 레거시 | 신규 |
| --- | --- | --- |
| 인증 없음·토큰 무효 | 401 `{"error":"unauthorized"}` / `{"error":"invalid token"}` | 401 `AUTH_UNAUTHORIZED` / `AUTH_INVALID_TOKEN` |
| path id가 UUID가 아님 | 400 `{"error":"invalid workspace id"}` | 400 `COMMON_BAD_REQUEST` |
| 워크스페이스 없음 | 403 (raw error 문자열) | **404 `WORKSPACE_NOT_FOUND`** |
| 멤버가 아님 | 403 (raw error 문자열) | **403 `AUTH_FORBIDDEN`** |

`WORKSPACE_NOT_FOUND`는 이미 [에러 코드표](../../spec/api-response-error-codes.md)에 있다.
미존재와 권한 없음을 나누면 워크스페이스 존재 여부가 드러나지만, 식별자가 추측 불가능한 UUID라
열거 위험이 없다고 판단한다.

### 4.5 차등 비교 결과

[차등 비교 하네스](../../local-development.md#레거시-차등-비교)로 두 서버에 같은 요청을 보내 확인한
결과다(`MOM-0877`, 케이스 `H020-*`, `H022-*`).

성공 응답 4건(목록, 빈 목록, 단건, `description` 없는 단건)은 **완전히 동일하다**. 래퍼 유무,
배열 정렬, `description` 생략, 타임스탬프 직렬화까지 차이가 없다.

차이는 4건이고 모두 4.4가 확정한 Standard 모드 에러다.

| 케이스 | 레거시 | 신규 |
| --- | --- | --- |
| 인증 없음 | 401 `{"error":"unauthorized"}` | 401 `AUTH_UNAUTHORIZED` |
| path id가 UUID 아님 | 400 `{"error":"invalid workspace id"}` | 400 `COMMON_BAD_REQUEST` |
| 멤버가 아님 | 403 `{"error":"access denied"}` | 403 `AUTH_FORBIDDEN` |
| 워크스페이스 없음 | 403 `{"error":"access denied"}` | 404 `WORKSPACE_NOT_FOUND` |

의도하지 않은 차이는 없다. 다만 신규 403·404 body는 4.4 표에 없던 `details.workspace_id`를
함께 싣는다. 표준 에러 형식이 허용하는 필드이고 요청 path에 이미 드러난 값이라 계약 변경으로
보지 않되, 여기 기록해 둔다.

## 5. Target 설계

웹 HTTP 표면은 신규 orchestration module `:web`이 소유한다. `:mobile`과 같은 자리이며,
capability 모듈의 public API만 조합하고 도메인 정책을 소유하지 않는다
([아키텍처](../../rules/architecture.md) 모듈 경계).

capability 모듈에 웹 presentation을 두지 않는 이유는 의존 방향이다. 현재 그래프에서 `:workspace`는
`:common`, `:user`만 참조하는 바닥 모듈이고 `:project`가 그것을 참조한다. 웹 응답이 다른
capability의 데이터를 합성하는 순간 `:workspace → :project`가 필요해져 순환이 생긴다. 레거시 웹
응답은 실제로 카운트·진행률을 싣고, H023 snapshot은 7개 capability를 합성한다. `:mobile`이 최상위
표면으로 앉아 이 문제를 피하는 것과 같은 이유로 `:web`을 둔다.

이 슬라이스 자체는 합성이 없지만, 모듈 결정은 남은 웹 endpoint 전체에 적용된다. 컨트롤러가 하나뿐인
지금이 방향을 세우는 가장 싼 시점이다.

```text
modules/web/src/main/java/works/momens/server/web/
└── workspace/
    ├── package-info.java                # 신규. Modulith nested 모듈 선언
    ├── WorkspaceController.java         # 신규. /api/workspaces, version "1"
    ├── WorkspaceControllerDocs.java     # 신규. OpenAPI
    ├── WorkspaceService.java            # 신규. workspace public API 조합, 에러 선택
    └── dto/response/
        ├── WorkspaceResponse.java       # 신규
        └── WorkspaceListResponse.java   # 신규

modules/workspace/src/main/java/works/momens/server/workspace/
├── WorkspaceErrorCode.java              # 신규. WORKSPACE_NOT_FOUND
├── WorkspaceReader.java                 # 신규. public API. 목록·단건 조회
├── WorkspaceDetail.java                 # 신규. public API. 조회 결과 record
└── internal/
    ├── WorkspaceReaderImpl.java         # 신규
    └── WorkspaceRepository.java         # 변경. 멤버십 조인 목록 조회 1개 추가
```

- `:web` 모듈을 `settings.gradle`, `app/build.gradle` 의존성, `Dockerfile`의 build script COPY
  목록에 함께 추가한다(`verifyDockerModuleBuildScripts`가 누락을 잡는다).
- `:web`은 `:common`과 `:workspace`만 의존한다. capability가 늘어날 때마다 의존을 추가한다.
- `WorkspaceReader`는 `Optional`을 반환하고 에러 선택은 `:web`이 한다. `ProjectReader`와 같은 방식.
- `:auth`의 `BearerTokenResolver`에 `session_token` 쿠키 fallback을 더한다(ADR-0017). 쿠키
  이름은 설정으로 두지 않고 전환기 상수로 둔다. 제거 조건을 주석에 남긴다.
- `WorkspaceRepository`에 멤버십 조인 목록 조회를 추가한다. 정렬은 쿼리에서 고정한다. 단건 조회는
  `JpaRepository.findById`를 그대로 쓰므로 추가할 메서드가 없다.
  - MOM-0894에서 이 조회를 제거했습니다. 멤버십은 `membership`의 공개 계약으로 확인하고,
    `WorkspaceRepository`는 확정된 ID를 기준으로 자신의 테이블만 조회합니다. 정렬 기준과 응답은
    변경하지 않았습니다.
- 권한 판정은 `WorkspaceMembershipReader.roleOf`를 재사용한다.
- 목록은 멤버십 조인이 필터라 별도 권한 검사를 하지 않는다.
- 쓰기가 없어 트랜잭션 경계와 outbox는 이 슬라이스의 범위 밖이다.
- `:web` `build.gradle`에 `spring-boot-starter-webmvc`(main)와
  `spring-boot-starter-webmvc-test`(test)를 둔다. springdoc은 `app`이 소유하므로 모듈에 추가하지
  않는다. `:workspace`는 웹 의존성을 갖지 않는다.

## 6. 전환과 롤백

ingress는 **요청 경로 접두사**로 분기한다. `api.momens.works`에서 `/api`로 시작하는 요청은
`momens-server`로, 접두사가 없는 나머지 경로는 레거시 `momens-api`로 간다
(`k8s/manifests/apps/momens-server/ingress.yaml`, ingress-nginx longest-prefix matching).
path rewrite는 하지 않는다. `API-Version` 헤더는 버전 선택용이며 라우팅에 관여하지 않는다
([ADR-0006](../../adr/0006-api-path-and-versioning-policy.md)).

따라서 전환 단위는 **클라이언트 배포**다. 웹이 path를 `/workspaces`에서 `/api/workspaces`로
바꾸면 그 순간 신규 서버로 넘어가고, 되돌리는 것도 클라이언트 배포다. `/api` 라우팅 규칙은 이미
서 있으므로 이 슬라이스에 ingress 변경은 없다. 서버는 두 endpoint를 추가할 뿐이며 레거시
endpoint는 그대로 살아 있다.

쓰기·projection이 없어 데이터 보상 절차는 필요 없다.

**구현 범위와 전환 범위를 분리한다.** 두 endpoint를 함께 구현하되, 이번에 전환하는 것은 H020뿐이다.

웹 사용처를 확인한 결과 두 endpoint의 성격이 달랐다.

| endpoint | 웹 사용처 | 이번 전환 |
| --- | --- | --- |
| H020 `GET /workspaces` | 세션 확인 직후 호출해 워크스페이스 개수로 로그인·온보딩 상태를 판단하는 핵심 경로 (`momens-fe/src/api/useApiSession.ts`) | **포함** |
| H022 `GET /workspaces/:id` | snapshot 조회 실패 시의 폴백에서만 호출. 해당 코드는 모든 배포 API가 `/snapshot`을 제공하면 삭제한다고 주석에 명시돼 있다 (`momens-fe/src/api/workspaceSnapshot.ts`) | 제외 |

H022는 서버에 구현해 두되 클라이언트를 옮기지 않는다. 유일한 소비자가 snapshot 폴백이므로 H023
계약을 잠글 때 이 endpoint가 계속 필요한지와 함께 전환 여부를 판단한다.

구현을 함께 하는 이유는 두 endpoint가 같은 reader와 DTO를 쓰기 때문이다. 지금 빼면 나중에 같은
코드를 다시 열어야 한다.

## 7. 미결정 사항

구현 중 조용히 정하지 않는다.

1. ~~**4.4 에러 응답 모드의 FE 합의.**~~ — 해소. Standard로 확정됐고(`MOM-0851`) 이후 웹 이관
   endpoint 전면으로 확대돼 이관 원장 공통 전환 규칙에 반영됐다(`MOM-0856`).
2. 레거시 미들웨어의 토큰 추출 fallback(`Authorization` 헤더·`access_token` 쿠키) 반영 시점.
   기준선 SHA 기준으로 아직 반영되지 않았고, 이 슬라이스의 선행 조건은 아니다.
3. 신규 endpoint로 전환한 뒤 레거시 `GET /workspaces`, `GET /workspaces/:id`를 retire하는 시점.

## 8. 후속 작업

1. `MOM-0851` [Feat] 웹 워크스페이스 조회 endpoint 이관 (H020, H022) — 위 계약대로 구현과 테스트
2. 구현 뒤 원장의 H020·H022 상태를 `contract_locked`에서 `implemented`로 갱신
