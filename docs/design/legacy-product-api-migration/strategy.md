# 레거시 Product API 이관 전략

상태: Draft

기준일: 2026-08-13

기준 레거시: `momens-api` `origin/main` `71bbd07614fd2aef4dec726bafdf86c1bd097ba6`

## 목적

이 문서는 Go/Gin `momens-api`의 로직을 Spring `momens-server`로 옮기는 실행 전략을 정의한다.
개별 기능의 상세 설계나 일정표가 아니라, 어떤 단위로 조사·구현·검증·전환하고 언제 이관이 끝났다고
판단할지를 정한다.

현재 모바일 MVP 요구사항 문서는 웹 API 이관을 로드맵 단계 C, 레거시 종료를 단계 D로만 구분한다.
그 구분은 범위를 설명하지만 실제 이관 단위, 레거시 웹 트래픽 컷오버, writer 소유권, projection,
롤백 조건을 정하지 않는다. 이 문서는 그 실행 규칙을 보완한다.

관련 상시 규칙과 결정은 [아키텍처](../../rules/architecture.md), [영속성](../../rules/persistence.md),
[API 버저닝](../../spec/api-versioning.md), [API 응답과 에러 코드](../../spec/api-response-error-codes.md),
[인증 세션·전송 모델](../../adr/0003-auth-session-transport-model.md),
[토큰 발급·검증 스택](../../adr/0004-token-issuance-verification-stack.md),
[refresh token 저장 모델](../../adr/0005-refresh-token-storage-model.md),
[사용자 신원 식별](../../adr/0016-user-identity-key-google-sub.md),
[projection 경계](../../adr/0008-outbox-worker-projection-boundary.md)를 따른다. 현재 모듈 책임은
[모듈 맵](../module-map.md)을 기준으로 하되, 이관 중 발견한 충돌은 아래 미결정 절차로 다시 확정한다.

## 범위

포함한다.

- 레거시 HTTP·protocol·background runtime 표면의 전수 조사와 분류
- 최소 수직 슬라이스의 선정과 완료 기준
- API 계약 보존·변경 절차
- 확정된 인증 계약과 레거시 웹 트래픽 컷오버의 결정 지점
- aggregate writer와 retrieval projection 소유권 전환
- 공유 DB 전환기 검증과 롤백 규칙
- 이관 상태 원장과 레거시 종료 조건

포함하지 않는다.

- 개별 endpoint의 request/response 상세 설계
- 구현 일정과 담당자 배정
- prod 스키마·필수 설정·수기 운영 의무를 실제로 반영하는 작업
- ingress, 배포 manifest, DNS 변경의 구체 명령
- `momens-worker`·`momens-retrieval` 내부 구현 상세

## 기준선

### 코드 기준

이관 분석의 정본은 계획 문서가 아니라 고정된 레거시 commit의 코드와 테스트다.

1. 슬라이스를 계획할 때 `momens-api origin/main`을 갱신하고 commit SHA를 원장에 기록한다.
2. route → handler → service → repository → domain model → migration → test 순서로 추적한다.
3. 레거시 문서와 코드가 다르면 코드를 현재 동작으로 보고 차이를 이관 원장에 남긴다.
4. 분석 뒤 레거시 `origin/main`이 변경되면 대상 파일의 diff를 확인하고 기준 SHA를 갱신한다.
5. 구현 중 레거시에 같은 capability 변경이 들어오면 컷오버 전에 계약 추적을 다시 수행한다.

2026-08-13 기준 레거시 라우터에는 조건부 등록을 포함해 96개의 route declaration이 있다. 반면
신규 서버에 구현된 공개 handler mapping은 모바일·인증·사용자 표면 중심이다. 따라서 기존 문서의
도메인 매핑 요약만으로는 잔여 범위를 판단하지 않고 route 전수 원장을 사용한다.

### 문서 우선순위

충돌 시 아래 순서로 판단한다.

1. 제품 의미와 정책: `teams`의 PRD·ADR·용어집
2. 신규 서버 외부 계약: `docs/spec/`
3. 신규 서버 구조와 현재 책임: `docs/design/`, `docs/adr/`, `docs/rules/`
4. 레거시 현재 동작: 고정 SHA의 코드와 테스트
5. 레거시 설명 문서: `momens-api/docs/`

문서에 없는 제품 결정을 레거시 코드만 보고 신규 정책으로 확정하지 않는다. 레거시 동작을 그대로
보존할지 바꿀지는 별도 결정으로 드러낸다.

## 표면 분류

레거시의 HTTP·비-HTTP 실행 표면을 하나의 REST 규칙으로 처리하지 않는다.

| 표면 | 예 | 이관 원칙 |
| --- | --- | --- |
| Product JSON API | workspace, project, task, memory, source 조회·명령 | 신규 경로는 `/api`, handler `version = "1"`; 계약 모드를 endpoint별 기록 |
| Product 인증 | Google login/callback, session refresh/logout, `/me` | 확정된 신규 웹 인증 계약을 사용하고 클라이언트와 컷오버를 함께 검증 |
| OAuth protocol | `/.well-known/*`, `/oauth/*` | 표준이 정한 path·method·content type을 우선하며 Product API path 규칙의 예외를 명시 |
| MCP transport | `/mcp` | MCP·OAuth 계약과 기존 grant/token 처리 방식을 별도 슬라이스로 추적 |
| 외부 webhook | `/slack/events`, source OAuth callback | 외부 provider 검증·재시도·redirect URI와 무중단 인계 절차를 별도 추적 |
| 운영 표면 | `/health`, actuator | 제품 로직 이관과 분리하고 배포·관측성 기준으로 검증 |
| 백그라운드 런타임 | scheduler, managed goroutine, startup migration·backfill | 시작 조건·side effect·재시도·멱등성·종료 순서와 대체 owner를 추적 |
| 오프라인 도구 | seed, eval, CLI | 런타임 이관 대상이 아님. 대체·폐기·유지를 명시적으로 분류 |

`/api`와 `API-Version` 규칙은 Product JSON API의 규칙이다. 표준 protocol과 provider callback에
그 규칙을 억지로 적용하지 않는다. 예외 경로는 해당 표면의 ADR 또는 spec에서 소유한다.

## 이관 단위

### 구현 단위: 최소 수직 슬라이스

구현은 한 사용자 행위를 끝까지 옮기는 최소 수직 슬라이스로 나눈다.

- 하나 이상의 밀접한 endpoint
- request parsing과 validation
- 인증·인가 규칙
- application/domain 규칙
- repository query와 transaction
- 필요한 schema mapping
- 성공·실패 응답 계약
- 단위·통합·계약 테스트

같은 Go 파일에 있다는 이유로 관련 없는 endpoint를 함께 옮기지 않는다. 반대로 하나의 사용자 행위가
여러 endpoint를 반드시 함께 요구하면 파일이 달라도 같은 슬라이스로 묶을 수 있다.

기본적으로 한 슬라이스는 하나의 집중된 PR 안에서 구현·검증할 수 있어야 한다. 너무 크면 controller,
service, repository 같은 수평 레이어가 아니라 독립적으로 검증 가능한 사용자 행위나 공개 계약을
경계로 다시 나눈다.

### 운영 전환 단위: read와 write를 구분

작은 구현 단위와 실제 트래픽 전환 단위는 같지 않을 수 있다.

- **read-only endpoint**는 인증과 응답 계약이 준비되면 endpoint 단위로 전환할 수 있다.
- **write endpoint**는 같은 aggregate를 변경하는 모든 경로와 비-HTTP writer를 함께 조사한다.
- 원칙적으로 aggregate의 writer는 한 시점에 하나만 둔다. 요청 복제나 dual-write를 기본 전략으로 사용하지
  않는다.
- 한 aggregate의 일부 명령만 신규 서버로 보내 두 서버가 동시에 상태를 변경하게 하지 않는다.
- 외부 webhook·scheduler·MCP tool도 같은 aggregate를 쓴다면 writer 전환 범위에 포함한다.

예를 들어 task는 REST create/update/delete뿐 아니라 Slack action과 MCP tool에서도 생성·변경된다.
따라서 REST handler 하나를 구현한 것만으로 task write 컷오버 준비가 끝난 것이 아니다.

여기서 aggregate는 한 transaction에서 우연히 함께 변경된 모든 행의 묶음이 아니라, 하나의 root를 통해
불변식을 원자적으로 지키는 일관성 경계다. 같은 aggregate의 write entry point는 서로 다른 PR에서 구현할
수 있지만 운영 writer 전환은 함께 해야 한다. read-only entry point는 이 전환 단위에 포함하지 않는다.

`users`는 [ADR-0016](../../adr/0016-user-identity-key-google-sub.md)에 따라 이 원칙의 한시적 예외다. 모바일이
신규 인증을 사용하는 동안에도 레거시 웹 로그인이 남아 있어 두 서버가 같은 `users` 행을 쓸 수 있다. 이는
한 요청을 두 서버로 복제하는 dual-write가 아니라 클라이언트 경로별 writer가 공존하는 상태다. 이관 원장에는
두 현재 writer, ADR-0016의 예외 근거, 레거시 `users` write 중단 조건을 함께 기록한다. 신규 서버가 유일한
writer가 된 뒤 MOM-0836으로 `users.email` UNIQUE 제약을 제거한다.

### 슬라이스 선정 기준

첫 슬라이스를 포함한 구현 순서는 아래 항목으로 비교한 뒤 별도 작업에서 확정한다.

- 사용자 가치와 현재 클라이언트 사용 여부
- 읽기 전용인지, writer 전환이 필요한지
- 인증·권한 의존성
- 다른 aggregate와의 transaction 결합도
- 외부 provider·worker·retrieval 의존성
- 레거시 테스트와 계약 근거의 충분성
- 롤백 시 레거시가 신규 데이터 형상을 읽고 쓸 수 있는지

이 문서는 특정 도메인을 첫 슬라이스로 고정하지 않는다. 후보를 고를 때 위 근거와 제외 endpoint를
Momens 작업 본문에 남긴다.

## 계약 전략

### path와 version

- Product API 신규 경로는 `/api` prefix를 사용한다.
- 모든 Product API handler는 `version = "1"`을 갖는다.
- 레거시 path alias는 만들지 않는다.
- 클라이언트 계약은 `API-Version: 1`이고, 전환기의 기본 version fallback은 기존 ADR-0006을 따른다.

### response mode

각 endpoint는 구현 전에 다음 중 하나로 분류한다.

- **Legacy compatible**: HTTP status와 성공·실패 JSON body shape를 보존한다.
- **Standard**: 신규 계약 또는 클라이언트와 합의한 개편 계약을 사용한다.

단순히 Java DTO를 새로 만든다는 이유로 Standard 모드로 바꾸지 않는다. 계약 변경에는 클라이언트
합의와 spec 갱신이 필요하다. path·version 변경과 response body 호환 여부는 독립적으로 기록한다.

### characterization

레거시 동작을 보존하는 슬라이스는 구현 전에 최소 다음 사례를 고정한다.

- 정상 응답 status와 body
- 빈 목록·nullable·필드 생략
- validation 실패
- 인증 없음·잘못된 인증
- 권한 부족
- 리소스 없음과 상태 충돌
- soft-delete 데이터 처리
- pagination, filter, sort, default 값

같은 fixture와 요청을 두 구현에 적용해 결과를 비교한다. 이 비교는
[차등 비교 하네스](../../local-development.md#레거시-차등-비교)(`scripts/legacy-diff/`)로 실행한다.
하네스는 고정 id·시각 픽스처를 양쪽 DB에 같은 내용으로 심어 값까지 그대로 비교하므로, 로컬
비교에서는 정규화가 JSON 키 정렬 하나뿐이다. dev 실서버처럼 값을 고정할 수 없는 대상에는
`--normalize`로 UUID·타임스탬프를 자리표시자로 바꾸고 shape만 비교한다.

diff 출력은 해당 슬라이스의 계약 문서에 근거로 남긴다. 계약 문서가 확정한 차이 외의 diff가
남아 있으면 `contract_locked`로 올리지 않는다. 레거시 테스트가 없으면
handler/service/repository 추적 결과를 characterization test의 근거로 함께 남긴다.

## 확정된 웹 인증 계약과 트래픽 전환

레거시는 `session_token` 단일 쿠키를 사용하고 신규 서버는 `access_token`·`refresh_token` 및 모바일
Bearer token을 사용한다. 두 서버가 같은 DB를 쓴다는 사실만으로 인증 세션이 호환되지는 않는다.

신규 웹 인증 계약과 서버 구현은 이미 확정·완료됐다. 레거시 `session_token`은 신규 서버가 지원할
호환 계약이 아니라 최종 컷오버에서 폐기할 대상이다.

- 모바일·웹은 공통 access + refresh 세션 코어를 사용한다.
- 웹은 서버 주도 Google Authorization Code + state·PKCE 흐름과 HttpOnly 쿠키를 사용한다.
- 프런트와 API 서버는 same-domain(same-site)으로 배포하고 CSRF는 별도 토큰 없이 SameSite 쿠키로
  방어한다. 배포 형태가 cross-domain으로 바뀌면 이 결정을 다시 검토한다.
- refresh token은 PostgreSQL 세션 원장에 저장하고 회전·폐기한다.
- 사용자 신원은 이메일이 아니라 `(provider, provider_user_id)`로 식별하며 Google `sub`을
  `provider_user_id`로 사용한다.
- 로그인·콜백·웹 refresh·logout의 경로와 응답 계약은
  [API 응답과 에러 코드](../../spec/api-response-error-codes.md)를 따른다.

미확정인 것은 신규 인증 모델이 아니라 레거시와 신규 서버 사이의 **웹 트래픽 전환 단위**다. 다음
상태를 구분한다.

- **구현 병행**: 신규 `/api` endpoint를 구현·검증하지만 웹 운영 트래픽은 계속 레거시를 사용한다.
- **배포 병행**: 두 서버가 함께 떠 있어도 한 웹 세션은 컷오버 전까지 레거시만 사용한다.
- **혼합 트래픽**: 한 웹 세션이 레거시 root endpoint와 신규 `/api` endpoint를 함께 호출한다.

구현·배포 병행에는 인증 브리지가 필요하지 않다. 혼합 트래픽을 사용하지 않고 필요한 웹 기능을
준비한 뒤 신규 인증과 Product API를 함께 전환할 수도 있다. 혼합 트래픽이 필요하다면 아래 호환 방식
중 하나를 명시적으로 결정해야 한다.

1. 신규 서버가 전환기 동안 레거시 `session_token`을 제한적으로 신뢰한다.
2. 레거시 서버가 신규 access token을 신뢰한다.
3. 로그인 과정이 두 서버의 세션을 함께 만든다.

방식 1과 2를 시점을 나눠 모두 채택했다. 웹 컷오버 **이전**에는 방식 1로 신규 서버가 레거시
`session_token`을 수용하고([ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md)),
컷오버 **이후**에는 방식 2로 레거시가 신규 `access_token`을 수용한다
([ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)). 방식 3은 두 ADR
모두에서 기각했다.

호환 방식을 도입하면 ADR-0003의 레거시 단일 세션 폐기 결정에 대한 한시적 예외가 되므로 보안 모델,
롤백, 세션 폐기, 클라이언트 공수를 비교한 ADR이 필요하다. 혼합 트래픽을 사용하지 않는다면 별도 인증
ADR 대신 이관 원장과 컷오버 계획에 전환 단위와 롤백 조건을 기록한다. 어느 쪽이든 “웹이 병행 운영될
수 있다”는 문구만으로 endpoint별 운영 전환을 승인하지 않는다.

## writer와 projection 전환

### 단일 writer

공유 DB 전환기에도 앞서 명시한 `users` 예외를 제외하고 aggregate별 단일 writer를 유지한다.

1. 이관 원장에 현재 writer를 기록한다.
2. REST 외 writer(webhook, MCP, Slack action, scheduler, worker)를 모두 찾는다.
3. 신규 서버가 같은 데이터와 불변식을 처리할 수 있는지 검증한다.
4. writer 트래픽을 한 단위로 신규 경로에 전환한다.
5. 레거시 writer를 비활성화하거나 도달 불가능하게 만든다.
6. 관측 후 레거시 코드를 제거한다.

읽기는 양쪽에서 가능하지만 쓰기는 동시에 활성화하지 않는다. DB constraint가 충돌을 막아 준다는
이유로 dual-write를 허용하지 않는다.

### retrieval projection

레거시 `momens-api`는 task·decision·blocker·memory write와 retrieval projection을 같은 transaction에서
처리한다. 신규 서버는 domain write transaction에서 outbox를 남기고 worker가 projection을 처리하는
방향이다. 다만 2026-08-14 기준 `momens-worker@9c0c8a7`에는 outbox consumer가 없고, 수집 transaction
안에서 `retrieval_documents`와 `retrieval_events`를 직접 쓰는 projection만 구현돼 있다.

따라서 projection 대상 aggregate의 write 컷오버에는 다음이 필요하다.

- 신규 write가 요구하는 outbox event 계약
- worker consumer와 domain projection 지원 여부
- event idempotency와 재처리 방식
- 기존 문서 ID와 신규 문서 ID의 충돌·중복 여부
- projection 지연·실패 관측
- 컷오버 시점 이전 데이터의 backfill 또는 재projection 필요 여부

신규 서버가 write를 넘겨받는 aggregate는 같은 transaction에서 필요한 outbox event를 남길 책임을
가진다. worker에는 ADR-0008의 목표 상태에 맞는 outbox 소비 기반(offset, 멱등, 재시도, DLQ)과 aggregate별
hydrate·projection 처리가 필요하다. 공통 소비 기반은 하나의 cross-repository 의존성으로 추적하되 task,
decision, blocker, memory의 이벤트 계약과 projector 지원은 각각 구분한다. 이 의존성은 분석이나 Product
API 구현 전체의 일률적인 선행 조건이 아니라 projection 대상 aggregate의 `cutover_ready` 게이트다. 전수
원장에는 공통 의존성을 먼저 등록하고 각 aggregate 행의 `projection/external` 필드에서 필요한 구현 작업을
참조한다.

## prod 운영 준비 조건

prod 배포 전에는 [prod 운영 준비 대장](../../prod-readiness-ledger.md)에서 다음 두 종류를 확인한다.

- **prod 필수 설정**: 기본값 없는 환경변수와 Secret·ConfigMap 주입 상태
- **수기 prod 의무**: provider 등록, OAuth redirect URI, ADC·IAM, DNS·ingress·TLS, 배포 순서처럼
  코드로 완전히 검증할 수 없는 조건

스키마는 운영 준비 대장의 책임이 아니다. 소유권은
[ADR-0019](../../adr/0019-prod-schema-ownership-transfer.md), 상시 규칙과 전환 중 의무는
[데이터 규칙](../../rules/persistence.md), 부트스트랩 방식·롤백·실행 순서는
[prod 스키마 주도권 이전 설계](../prod-schema-ownership-transfer.md)가 소유한다. 공유 DB를 사용하므로
일반적인 데이터 복사 migration은 필요하지 않지만, 각 슬라이스는 필요한 마이그레이션 적용과 writer
호환성을 `cutover_ready` 증거로 확인한다.

스키마 release gate는 폐지했다. 자동 release gate는 prod 필수 설정의 `required` 상태만 검사하고,
수기 prod 의무는 자동으로 차단하지 않는다. 이관 원장의 **prod gate** 필드는 슬라이스가 직접 요구하는
스키마·writer·projection, 필수 설정, 수기 의무와 적용 범위를 기록한다. 이 조건은 로직 분석이나 구현의
일률적인 선행 조건이 아니라 해당 capability를 prod로 전환하기 위한 조건이다.

## 단계와 게이트

### 1. Trace

- 기준 SHA 고정
- route와 비-HTTP entry point 추적
- request/response/error/RBAC/transaction/schema/config/lifecycle/test 기록
- 포함·제외 entry point 명시

완료 기준: 독립적으로 구현 가능한 수직 슬라이스와 미결정 사항이 드러난다.

### 2. Contract lock

- Legacy compatible 또는 Standard 선택
- characterization 사례 고정
- target module과 public API 경계 확인
- writer·projection·외부 의존 확인
- 새 의존성이 필요하면 기존 문서의 허용 여부 또는 팀 결정을 확인

완료 기준: 클라이언트와 다른 서버가 기대할 계약을 구현 전에 검증할 수 있다.

### 3. Implement

- controller부터 persistence까지 수직 구현
- 필요한 단위·slice·repository 통합 테스트
- OpenAPI 성공·실패 예시
- Modulith 경계 검증

완료 기준: 신규 endpoint가 local/test에서 독립적으로 동작하고 계약 테스트가 통과한다.

### 4. Cutover ready

- 확정된 신규 웹 인증 계약 및 필요한 경우 세션 공존 방식 준비
- aggregate 단일 writer 전환 계획
- projection·webhook·MCP·background runtime 등 비-HTTP 경로 준비
- 스키마·writer·projection 증거와 prod 운영 준비 대장의 필수 설정·수기 의무를 각각 검증
- rollback 호환성 검증

완료 기준: 해당 prod gate가 충족됐고, 트래픽을 바꿔도 이중 write와 누락이 없으며 되돌릴 수 있다.

### 5. Cutover

- 합의된 단위의 client/ingress/entry point 전환
- 레거시 writer 차단
- error rate, latency, DB constraint, outbox·projection 지연 관측
- rollback window 유지

완료 기준: 합의한 관측 기간 동안 신규 경로가 단일 owner로 동작한다.

### 6. Retire

- 레거시 route와 비-HTTP entry point 제거 또는 비활성 확인
- 임시 호환 코드·feature flag 제거
- runbook·spec·원장 갱신

완료 기준: 해당 capability에 레거시 트래픽·writer·운영 의존이 없다.

## 롤백

이 절은 원칙만 정한다. 웹 컷오버의 단계별 실행·롤백 절차는
[웹 컷오버 실행과 rollback runbook](cutover.md)에 있다.

롤백은 deploy rollback과 writer rollback을 구분한다.

- **deploy rollback**: 신규 서버 코드나 routing을 직전 버전으로 되돌린다.
- **writer rollback**: aggregate writer를 레거시로 되돌린다. 신규 서버가 쓴 행을 레거시가 안전하게
  읽고 변경할 수 있을 때만 가능하다.

write 컷오버 전 다음을 확인한다.

- 신규 컬럼이 레거시 INSERT/UPDATE를 깨지 않는가
- 신규 enum·status를 레거시가 읽을 수 있는가
- label·sequence·soft-delete 규칙이 같은가
- 신규 서버가 만든 relation과 FK를 레거시가 보존하는가
- projection을 다시 레거시 방식으로 돌릴 때 중복이나 누락이 없는가
- 신규 서버에서만 이해하는 데이터가 이미 생성됐다면 보상 절차가 있는가

확인되지 않은 경우 writer rollback 가능하다고 기록하지 않는다. 읽기 전환은 routing rollback으로
되돌릴 수 있어도 쓰기 전환은 데이터 호환성이 없으면 되돌릴 수 없다.

## 이관 원장

진행 상태는 Momens task 목록이나 이 문서의 서술만으로 추론하지 않고 별도 원장으로 관리한다. 원장은
최초 route 전수 조사 작업에서 생성한다.

필수 필드는 다음과 같다.

| 필드 | 내용 |
| --- | --- |
| surface | Product API, auth, OAuth, MCP, webhook, operational, background runtime, offline |
| capability | workspace, task, memory 등 사용자 행위 또는 aggregate |
| legacy entry point | method/path 또는 webhook·tool·scheduler·goroutine·startup migration/backfill 이름 |
| legacy baseline | 분석한 commit SHA |
| legacy trace | handler/service/repository/model/migration/test 위치 |
| target | Gradle module, package, public API |
| contract | Legacy/Standard, status·body·version 정책 |
| auth/RBAC | 인증 수단과 권한 규칙 |
| writer | 현재 writer, 목표 writer, 전환 단위 |
| projection/external | worker, retrieval, provider, MCP 등 의존 |
| prod gate | 슬라이스별 cutover 직접 의존성과 prod 필수 설정·수기 의무 확인 위치 |
| client gate | FE·모바일·외부 provider 변경 |
| rollback | 되돌릴 수 있는 범위와 제한 |
| status | traced, contract_locked, implemented, cutover_ready, cutover, retired |
| task/PR | 구현·결정·전환 작업 링크 |

상태는 증거가 있을 때만 올린다. `implemented`는 코드가 존재한다는 뜻이고 `cutover`나 `retired`를
뜻하지 않는다. 모바일용으로 같은 aggregate 일부가 구현돼 있어도 레거시 웹 표면의 계약이 다르면
별도 entry로 추적한다.

## 레거시 전체 종료 조건

아래 조건이 모두 충족돼야 `momens-api`를 종료할 수 있다.

- 전수 원장의 runtime entry point가 모두 `retired`이거나 명시적으로 다른 시스템에 남는다.
- Product API와 Product 인증의 클라이언트 트래픽이 없다.
- OAuth metadata/token/grant, MCP, Slack/source webhook의 소유 서버가 확정되고 전환됐다.
- aggregate별 레거시 writer가 모두 중단됐다.
- background goroutine, scheduler, migration runner 등 HTTP 밖의 실행 경로가 남아 있지 않다.
- 신규 서버와 worker의 projection 경로가 필요한 aggregate를 모두 담당한다.
- 미사용 세션·OAuth grant·provider callback의 drain 또는 폐기 정책이 실행됐다.
- DB migration 권한과 runbook의 최종 소유자가 확정됐다.
- 레거시 image·deployment·secret·alert·dashboard 제거 작업이 별도 확인됐다.

코드가 이관됐다는 이유만으로 레거시 종료를 선언하지 않는다.

## 선행해서 확정할 결정

다음은 구현하면서 조용히 정하지 않는다.

1. ~~**레거시 웹 트래픽 컷오버**: 혼합 트래픽을 허용할지, 허용한다면 어떤 세션 브리지를 둘지.~~
   확정했다. Product capability별 혼합 전환은 하지 않고 인증과 Product API를 2단계로 나눈다.
   미이관 OAuth interaction·MCP grant UI만 한시적 legacy base로 격리하고 ADR-0018의 세션 브리지를
   유지한다([컷오버 문서](cutover.md), `MOM-0911`).
2. **첫 수직 슬라이스**: 위 선정 기준에 따른 대상과 제외 endpoint.
3. **retrieval 책임과 worker 소비 계약**: `module-map`의 신규 서버 `retrieval` 모듈 책임과 ADR-0008의
   worker projection 책임을 정합화하고, 공통 outbox 소비 기반과 aggregate별 hydrate·projection 경계를
   어떻게 나눌지.
4. **MCP/OAuth 모듈 경계**: 최신 레거시에 추가된 `mcpauth`·`mcpserver`의 목표 모듈과 grant/token
   이전 방식.
5. **웹·모바일 task 계약**: MOM-0773의 필드·기본값·진행률 정책 정합화.
6. **source_ref·signal evidence 관계**: MOM-0774의 스키마·조회 계약 정합화.
7. **모바일 workspace scope**: MOM-0845 결정이 공통 workspace/project API 계약에 주는 영향.

이 결정들은 전략 문서의 선행 조건이 아니라 각 영향 슬라이스의 `contract_locked` 또는
`cutover_ready` 게이트다.

## 다음 작업

1. 고정 SHA 기준 route·비-HTTP entry point 전수 원장을 만들고 공통 cross-repository 의존성을 등록한다.
2. worker outbox 소비 기반과 aggregate별 projection 구현 작업을 분리해 만들고 원장에 연결한다.
3. 원장에서 첫 수직 슬라이스 후보를 비교한다.
4. ~~혼합 웹 트래픽이 필요한지 판단하고, 필요할 때만 세션 공존 ADR을 작성한다.~~ 완료. Product
   capability별 혼합은 하지 않으며, 미이관 OAuth/MCP UI의 한시적 legacy base는 ADR-0018의 기존
   세션 공존 결정으로 처리한다([컷오버 문서](cutover.md), `MOM-0911`).
5. 선택한 슬라이스마다 `migrate-slice` 추적과 구현 작업을 별도 Momens task로 만든다.
