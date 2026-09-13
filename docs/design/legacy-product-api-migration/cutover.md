# 웹 컷오버 실행과 rollback runbook

상태: 전환 단위 확정 (1단계 착수 전)

작성일: 2026-09-08

레거시 기준선: `Momens-Works/momens-api@71bbd07614fd2aef4dec726bafdf86c1bd097ba6`

FE 기준선: `momens-fe@c230e82670856b5dda11367947680128b86c49b4`

관련 작업: `MOM-0911`

관련 문서: [이관 전략](strategy.md),
[이관 원장](ledger.md),
[ADR-0016](../../adr/0016-user-identity-key-google-sub.md),
[ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md),
[ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)

## 1. 읽는 법

컷오버를 **실행하는 사람**을 위한 문서다. 무엇을 어떤 순서로 뒤집고, 무엇을 보고, 어떻게
되돌리는지만 담는다.

웹 실사용 Product API 33개는 원장 기준으로 모두 `implemented`라 Product capability별 혼합 전환은
하지 않는다. 다만 같은 `MomensApiClient`를 쓰는 OAuth interaction·MCP grant UI
(H009~H011·H035·H036)는 Product API가 아니며 신규 서버에 이관되지 않았다. 2단계부터 이 표면만
`VITE_LEGACY_API_BASE_URL`로 격리하고, 나머지 XHR은 `VITE_API_BASE_URL`로 함께 전환한다. 이는
일반 endpoint 라우팅 계층이 아니라 이관하지 않기로 명시한 레거시 OAuth/MCP UI 하나의 한시적
예외다. 상세 결정 이력은 `MOM-0911`에 있다.

사용자는 현재 내부 팀 10명뿐이다. **전환 중 일시적인 인증 실패와 전원 재로그인을 허용하고 세션
연속성을 보장하지 않는다.** 다만 사용자가 명시적으로 로그아웃했는데 인증 상태가 남는 것은 세션
연속성과 다른 보안 동작이므로 허용하지 않는다.

**확정한 전환 단위는 인증 → Product API 2단계다.** 두 단계는 서로 다른 스위치를 뒤집고 서로
다른 게이트를 가진다.

이 순서는 ADR-0017에서 기각한 "웹 로그인을 먼저 신규 서버로 전환한다"는 대안과 판단 시점과
대상이 다르다. ADR-0017은 첫 workspace read 슬라이스를 인증 전환과 묶지 않고 가장 작은 read부터
독립적으로 전환하기 위한 결정이었다. 이 runbook은 웹 실사용 Product API 33개가 구현된 뒤 최종 FE
컷오버 단위를 정한다. 그사이 ADR-0018의 역방향 호환 bridge, 내부 사용자 전원 재로그인 허용,
Cloudflare FE deployment rollback 절차가 확정되어 당시의 "첫 슬라이스가 인증과 묶이고 로그인을
되돌리기 어렵다"는 기각 근거가 더는 현재 단계의 전제가 아니다. ADR-0017의 레거시
`session_token` 수용 결정 자체는 폐기하지 않으며, 1단계 rollback 경로가 열려 있는 동안 유지한다.

| | 1단계 인증 | 2단계 Product API |
| --- | --- | --- |
| 뒤집는 스위치 | `VITE_AUTH_LOGIN_URL`, `VITE_AUTH_LOGOUT_URL` | `VITE_API_BASE_URL`, `VITE_LEGACY_API_BASE_URL` |
| 움직이는 writer | `users` | 나머지 전 aggregate |
| 선행 게이트 | 3절 | 5절 |
| 저장소 밖 전제 | Google callback URI 병행 등록 | source provider callback URI 병행 등록 |
| 롤백 | FE deployment rollback + 재로그인 허용 | 데이터 호환성 확인 필요 |
| 착수 | 게이트 미해소 | 게이트 미해소 |

## 2. 전환 스위치

두 서버는 같은 host `api.momens.works`를 경로 prefix로 나눠 쓴다. ingress-nginx의 최장 prefix
매칭이 `/api/*`를 `momens-server`로, `/`를 `momens-api`로 보낸다
(`k8s/manifests/apps/momens-server/ingress.yaml`). **서버나 라우팅 규칙은 건드리지 않는다.**

| 스위치 | 무엇을 결정하는가 | 위치 |
| --- | --- | --- |
| `VITE_AUTH_LOGIN_URL` | 로그인 진입점. 브라우저 내비게이션이라 API client를 타지 않는다 | `src/api/config.ts:18` |
| `VITE_AUTH_LOGOUT_URL` | 로그아웃 요청만 신규 서버로 보낸다. 1단계에서 추가할 명시적 auth URL이다 | `MOM-0906` |
| `VITE_API_BASE_URL` | `MomensApiClient`의 모든 XHR. endpoint별 분기가 없다 | `src/api/config.ts:9`, `src/api/client.ts:58` |
| `VITE_LEGACY_API_BASE_URL` | H009~H011·H035·H036만 레거시 서버로 보낸다. 2단계에서 추가할 한시적 base다 | `MOM-0906` |
| Google callback URI 허용 목록 | 신규·레거시 로그인 callback을 provider가 허용하는가 | Google Cloud 콘솔 |
| source provider callback URI 허용 목록 | 신규·레거시 소스 연결 callback을 provider가 허용하는가 | GitHub·Slack·Notion·Figma 콘솔 |

Vite가 빌드 타임에 앞의 네 값을 굽는다. 전환은 FE 재빌드·재배포이고, 긴급 롤백은 Cloudflare의
직전 deployment rollback으로 먼저 닫은 뒤 Git의 컷오버 커밋을 revert한다(7.4).

login env와 API base의 독립은 조건부다. `VITE_AUTH_LOGIN_URL`이 비면 `baseUrl`에서 파생된다
(`src/api/config.ts:18-20`). `.env.production`이 값을 명시하고 있어서 독립이 성립하므로,
**그 값을 비우거나 지우면 `baseUrl` 전환이 로그인 진입점까지 함께 옮긴다.** 2단계 분할 전체가
이 한 줄에 걸려 있다.

## 3. 1단계 게이트

순서대로 닫는다.

1. **`MOM-0873` 두 서버 JWT 서명 키 동일성 확인.** 확인 대상이 리포에 불변식으로 적혀 있다 —
   `k8s/manifests/apps/momens-server/secret.example.yaml:25-28`의
   *"TRANSITION INVARIANT: keep this EQUAL to momens-api's JWT_SECRET during the dual-run"*.
   값이 다르면 전환 즉시 웹 전체가 401을 받고, 테스트로는 잡히지 않는다.
2. **Google OAuth 운영 설정 확인.** 신규 callback
   `https://api.momens.works/api/auth/google/callback`과 레거시 callback
   `https://api.momens.works/auth/google/callback`을 실제 web OAuth client에 병행 등록한다.
   `MOMENS_AUTH_GOOGLE_CLIENT_ID`·`MOMENS_AUTH_GOOGLE_CLIENT_SECRET`·
   `MOMENS_AUTH_GOOGLE_AUDIENCES`의 실제 값 조합을 확인하고, 신규 로그인 → callback → 쿠키 발급을
   prod에서 직접 검증한다. 운영 준비 대장의 Google OAuth 수기 의무가 `확인 필요`인 동안은 1단계를
   열지 않는다.
3. **`MOM-0904` 레거시 `RequireAuth`의 신규 `access_token` 수용.** 레거시는 `session_token`
   쿠키 하나만 읽으므로(`momens-api/internal/platform/httpx/middleware.go:74`) 이것 없이
   로그인만 전환하면 레거시 Product API와 MCP 재인증에 쓰는 consent·grant API
   (H009~H011·H035·H036)가 401이 된다. MCP를 계속 사용하므로 사용자 재로그인을 허용해도 이
   호환은 필요하다. 2단계 뒤에도 이 표면은 레거시로 가므로 G4가 해소될 때까지 유지한다.
4. **`MOM-0905` 신규 logout의 레거시 `session_token` 만료.** 1단계부터 FE 로그아웃을
   `POST /api/auth/web/logout`으로 직접 보내며, 신규 서버는 `access_token`(`Path=/`)과
   `refresh_token`(`Path=/api/auth`)을 각각 올바른 경로로 만료하고 refresh token을 폐기한다. 이때
   남은 `session_token`으로 레거시 보호 경로가 다시 인증되면 안 되므로 세 쿠키 만료를 배포 전에
   검증한다.

레거시 `Logout`을 교차 쿠키에 맞추지 않는다. `refresh_token`은 `Path=/api/auth`라 레거시
`/auth/logout` 요청에 실리지 않아 서버 측 폐기까지 할 수 없다. endpoint별 Product API 라우팅 계층을
만드는 대신 auth logout URL 하나만 분리한다.

## 4. 1단계 실행

```text
1. MOM-0873 확인 + Google OAuth 운영 설정 확인 뒤 깨끗한 브라우저 프로필에서 신규 로그인 smoke test
2. momens-api 배포 (RequireAuth의 access_token 수용)
   → 기존 session_token 경로가 회귀 없이 동작하는 것을 확인
3. 1번에서 받은 access_token만으로 레거시 /auth/me, GET /workspaces,
   GET /workspaces/:id/mcp-grants와 MCP 재인증 1회를 통과하는지 확인
4. momens-server 배포 (MOM-0905의 session_token 만료)
5. 내부 사용자에게 일시적인 인증 실패·전원 재로그인 가능성을 공지
6. FE: VITE_AUTH_LOGIN_URL과 VITE_AUTH_LOGOUT_URL만 신규 서버로 전환, 재빌드·재배포
7. 6절의 관측 창 진입
```

6번 전에 2번과 4번의 배포가 prod에 반영된 것을 확인한다. **순서가 뒤집히면 신규 로그인 사용자가
레거시 Product API에서 401을 받는다.**

### 4.1 건드리지 않는 것

- **`/auth/me`·`PATCH /auth/me`는 계속 레거시로 간다.** 게이트 3 이후 `access_token`으로
  통과한다. `PATCH`는 `users` write지만 ADR-0016의 한시 예외 범위 안이다.
- **자동 refresh를 넣지 않는다.** 레거시 세션도 24시간, 신규 access TTL도 24시간이라 만료 시
  재로그인 동작이 지금과 같다.

### 4.2 `MOM-0906`을 통째로 적용하지 않는다

`MOM-0906`은 이 문서 이전에 세운 티켓이라 컷오버를 전부-아니면-전무 스위치로 보고 **env 두 개
전환과 경로 수정(`/auth/me` → `/api/me`, `/auth/logout` → `/api/auth/web/logout`)을 한 묶음**으로
잡고 있다.

**1단계가 가져가는 것은 `VITE_AUTH_LOGIN_URL`과 별도 `VITE_AUTH_LOGOUT_URL` 전환이다.** logout은
명시적 신규 URL로 보내고, API client의 `/auth/logout` 경로는 쓰지 않는다. `/auth/me` → `/api/me`
수정은 base가 신규 서버를 가리킬 때에만 의미가 있고 먼저 적용하면 레거시에 없는 경로를 불러 404가
난다. Product API 경로 수정과 `VITE_LEGACY_API_BASE_URL` 도입은 2단계와 같은 배포에 묶는다.

## 5. 2단계 게이트

**미해소 게이트가 넷이라 지금 착수할 수 없다.** 착수 전에 `MOM-0906`을 4.2와 G4에 맞춰
쪼갠다.

### G1 — retrieval 투영 공백 (`MOM-0898`, `MOM-0956`, `MOM-0957`)

레거시는 task·decision·blocker·memory 쓰기를 같은 트랜잭션에서 retrieval 문서로 인라인
투영한다(`momens-api/internal/bootstrap/app.go:155`, `internal/retrieval/projection.go`). 신규
서버는 outbox에 이벤트를 쌓는데(`TaskWriterImpl`, `MemoryWriterImpl`) `momens-worker`에 소비자가
없다.

웹 컷오버로 실제 움직이는 것은 **task와 memory 둘**이다. decision·blocker의 웹 endpoint는 원장
기준 폴백 전용이거나 호출처가 없다.

**이 실패는 조용하다.** write는 성공하므로 5xx도 401도 나지 않고 6절의 관측 창에서 잡히지
않는다. `MOM-0898` 완료만으로 이 게이트를 닫지 않는다. 공통 consumer와 aggregate별 projector는
다음 체인 전체를 통과해야 한다.

1. `MOM-0898` — 공통 outbox polling·claim, offset, retry와 DLQ 또는 동등한 실패 격리
2. `MOM-0956` — task event hydrate·projector와 task별 prod E2E 투영 확인
3. `MOM-0957` — memory event hydrate·projector와 memory별 prod E2E 투영 확인
4. 검증 event가 처리 완료 상태가 되고 consumer offset이 전진함
5. retry 대기나 DLQ 또는 동등한 실패 격리 저장소에 검증 event가 남지 않음
6. retrieval 조회에서 task·memory 변경 결과를 확인함

현재 prod에는 시계열 수집이 없으므로 lag graph만으로 판정하지 않는다. `MOM-0898`과 두 projector
작업이 정한 조회 수단으로 event 처리 상태·offset·실패 잔여를 직접 확인하고, task와 memory의
증거를 각각 `MOM-0956`·`MOM-0957`에 남긴 뒤 2단계를 연다.

### G2 — `tasks`의 비-웹 레거시 writer (`MOM-0953`)

레거시에서 `task.Service`를 쓰는 write 경로는 둘이다 — MCP 서버(`internal/mcpserver/tools.go`)와
민수 액션(`internal/minsu/action/create_task.go`). `internal/slackbot/action.go`는 민수
`action.Dispatcher`에 위임하는 라우팅 래퍼이므로 별도 writer가 아니다.

두 표면은 ADR-0018로 컷오버 후에도 레거시에 남는다(원장 미결정 2번). Product API base는
하나뿐이라 2단계에서 웹 write가 함께 넘어가므로 `tasks`에 두 서버 writer가 공존한다. 원장의
「`tasks` target writer 구현과 운영 활성화」가 별도 결정과 rollback 조건을 먼저 기록하도록
요구한다.

### G3 — source provider OAuth 미배선 (`MOM-0954`)

`H041`·`H082`는 원장의 실사용인데 신규 서버의 provider 자격 증명이 prod에 없다.
`configmap.yaml`·`secret.example.yaml`에 `MOMENS_SOURCE_OAUTH_*`가 없고
`application.yml:134-153`의 provider 자격 증명과 redirect URI·success redirect URI·state
secret·token key 기본값이 비어 있다(`state-ttl`만 `10m`). `SourceInstallerImpl`의
`isConfigured()` 검사가 `SOURCE_PROVIDER_UNCONFIGURED`(**500**)를 던진다. 자격 증명과 callback
설정을 채워도 `MOMENS_SOURCE_OAUTH_SUCCESS_REDIRECT_URI`가 비어 있으면 연결 완료 뒤 FE로 302
이동하지 않고 callback JSON을 그대로 보여 준다.

G1과 달리 5xx로 드러나지만, 2단계에서 base를 뒤집는 순간 **소스 연동이 통째로 죽는다.**

레거시 콜백은 `https://api.momens.works/source-connections/oauth/callback`이고 신규 서버는
`/api` 접두사가 붙어 주소가 다르므로 네 provider 콘솔의 등록도 바꿔야 한다. **두 주소를 병행
등록해 두고 전환한다** — 신규만 등록한 채 되돌리면 레거시 콜백이 깨진다.

### G4 — 레거시 OAuth/MCP UI 라우팅 (`MOM-0906`)

FE의 OAuth interaction 조회·승인·거절(H009~H011)과 MCP grant 조회·폐기(H035·H036)는
`MomensApiClient`를 쓰지만 신규 서버에는 구현되지 않았다. 2단계에서 `VITE_API_BASE_URL`을
`https://api.momens.works/api`로 바꾸기만 하면 이 요청도 `/api` ingress를 타고 신규 서버로 가서
404가 된다.

`MOM-0906`에서 `VITE_LEGACY_API_BASE_URL=https://api.momens.works`을 추가하고 이 다섯 메서드만
legacy client를 쓰게 한다. 나머지 Product API에 endpoint별 분기를 허용하지 않는다. 이 한시적
base가 있는 동안 한 웹 세션이 두 서버를 호출하므로 `MOM-0904`의 신규 `access_token` 수용을
유지한다.

2단계 전환 전 두 client의 실제 요청 URL을 확인하고, 신규 MCP 연결 승인·MCP 재인증·grant 조회와
폐기를 각각 한 번 통과시킨다. 이 표면을 신규 서버로 이관할 때 legacy base와 `MOM-0904` 제거 조건을
별도로 기록한다.

### 함께 확인할 것

- **`MOM-0883` write 배포 후 검증 방침.** 2단계는 웹 write 전체를 옮기므로 이 결정이 없으면
  배포 후 확인 수단이 없다.
- 전략 문서 「롤백」의 데이터 호환성 6항목. 7.2를 따른다.

### 2단계 실행 순서

```text
1. G1~G4, MOM-0883과 데이터 호환성 6항목이 모두 닫힌 것을 확인
2. 검증용 workspace와 6.1의 task·memory smoke 데이터를 준비하고 ID를 MOM-0911에 기록
3. source provider의 신규·레거시 callback URI가 병행 등록된 것을 확인
4. task·memory projector가 prod에서 실행 중이고 offset·실패 잔여 조회 수단이 동작하는지 확인
5. FE: VITE_API_BASE_URL은 신규 /api, VITE_LEGACY_API_BASE_URL은 레거시 root로 설정
   → /auth/me를 신규 /api/me 계약에 맞추고 H009~H011·H035~H036만 legacy client로 분리
6. FE 재빌드·재배포 뒤 두 client의 실제 요청 URL을 확인
7. 6절의 2단계 smoke와 관측 창 진입
```

## 6. 관측

`momens-server`는 prod에서 지표를 프로세스 밖으로 내보내지 않는다. actuator는 `health`만
노출하고(`app/src/main/resources/application.yml:156-160`) tracing·OTLP export가 모두
비활성이며(`app/src/main/resources/application.yml:168-178`, `MOM-0834` 미착수) 클러스터에
수집기와 로그 집계가 없다. **시계열이 없다.** 볼 수
있는 것은 pod 로그와 ingress-nginx 액세스 로그뿐이고 둘 다 `kubectl`로만 보이며 보존되지 않는다.

`legacy_session_cookie` increase 0처럼 시계열이 필요한 판정은 이 문서가 다루지 않는다.
`MOM-0875`가 소유하고 `MOM-0834`를 선행으로 건다.

### 6.1 컷오버 판정 창

**전환을 실행한 사람이 그 자리에서 지켜본다.** 자리를 뜬 채로 전환하지 않는다.

각 단계 배포 직후 **최소 10분** 집중 관측하고 실행자와 다른 팀원 한 명, 총 2명이 아래 smoke를
독립적으로 완료한다. 전환 공지에 포함한 재로그인 자체와 두 FE 배포 사이의 일시적인 인증 실패는
rollback 신호로 세지 않는다.

| 단계 | 필수 smoke |
| --- | --- |
| 1단계 | Google 재로그인 → `/auth/me` → 워크스페이스 목록 → MCP grant 목록 → MCP 재인증 1회 → 로그아웃 → 보호 경로 401 → 재로그인 |
| 2단계 | Google 재로그인 → `/api/me` → workspace snapshot → task 생성·수정·삭제 → H084 confirm → H088 edit-and-confirm → H093 resolve → 각 task·memory의 retrieval 반영 확인 → source 연결 1회 → MCP 신규 연결 승인·재인증·grant 조회·폐기 |

2단계 전에 검증용 workspace와 서로 다른 pending memory 두 건(H084·H088), resolve 대상과 해결에
사용할 confirmed memory 두 건(H093)의 ID를 준비해 `MOM-0911`에 기록한다. 상태 전이가 끝난 레코드를
다른 smoke에 재사용하지 않는다.

smoke 요청의 기대 상태가 한 번이라도 어긋나면 즉시 되돌린다. 집중 관측 중 실제 사용에서 아래 신호가
나오면 같은 동작을 한 번만 재시도하고, 재현되면 되돌린다. 10분과 2명 중 하나라도 채우지 못하면
단계 완료로 판정하지 않는다.

| 신호 | 보는 곳 | 되돌리는 값 |
| --- | --- | --- |
| 전면 401 | 두 서버 pod 로그, ingress 액세스 로그의 상태 코드 | 로그인 성공 세션이 보호 경로에서 401을 받는 것이 **1건이라도** |
| 404 | 같은 곳 | 전환 전에 없던 경로의 404가 나타나면 |
| 5xx | 같은 곳 | 전환한 경로의 같은 동작에서 1회 재시도에도 재현되면 |
| write 실패 | pod 로그의 예외, DB constraint 위반 | 1건이라도 |

전면 401과 write 실패에 임계값을 두지 않는다. 둘 다 정상 상태에서 0이고, 1건이 보이면 그 뒤로
같은 실패가 모든 사용자에게 일어난다.

**2단계에서 404는 연쇄로 나타난다.** FE의 `loadWorkspaceSnapshotLegacy` 폴백은 snapshot이 404일
때만 동작하는데, 그 폴백이 부르는 H038·H039·H042·H044·H051은 신규 서버에 endpoint가 없다.
snapshot 하나가 404를 내면 5개 경로의 404가 함께 나타나므로, **404가 무더기로 보이면
snapshot부터 확인한다.**

집중 관측을 통과한 뒤 같은 날 안에 두 사람이 필수 smoke를 한 번 더 실행한다. 로그가 보존되지
않으므로 실행 시각·사용한 workspace·각 항목 결과·관련 로그를 그 자리에서 갈무리해 `MOM-0911`에
남긴다.

## 7. 롤백

deploy rollback과 writer rollback을 구분한다(전략 문서 「롤백」). **웹 컷오버의 롤백은 서버 배포가
아니라 FE env를 되돌리는 것이다.**

### 7.1 1단계

Cloudflare에서 직전 FE deployment로 롤백해 로그인과 로그아웃 요청 대상을 함께 레거시 상태로
되돌린다.

- 사용자는 레거시 로그인으로 `session_token`을 다시 발급받는다. 신규 서버는 ADR-0017에 따라 그
  쿠키를 계속 수용한다. 기존 세션의 연속성은 보장하지 않으며 필요하면 다시 로그인한다.
- **`momens-api`의 3절 게이트 3 변경은 되돌리지 않는다.** MCP 재인증의 consent·grant API가
  신규 `access_token`을 계속 받아야 하고, 이 변경은 양방향으로 안전하다.
- Google Cloud 콘솔의 두 callback URI를 모두 유지한다. 레거시 URI를 지우지 않았으므로 FE를
  되돌린 뒤 레거시 로그인이 다시 성립한다.
- `users`에 남은 신규 identity 행은 그대로 둔다. ADR-0016 예외가 두 writer 공존을 이미 허용해
  보상 절차가 필요 없다.

**1단계는 writer rollback이 필요 없다. FE를 되돌린 뒤의 재로그인은 허용된 롤백 비용이다.**

### 7.2 2단계

`VITE_API_BASE_URL`을 되돌리면 read는 닫히지만 그것만으로 전부 닫히지는 않는다.

- **provider redirect URI는 FE env가 아니다.** G3의 병행 등록을 해 두지 않고 신규 주소만
  등록한 채 되돌리면 레거시 콜백이 깨진다.
- **legacy base 분기는 FE와 함께 되돌린다.** 직전 deployment는 모든 XHR이 레거시 base를 쓰므로
  별도 라우팅 복구가 필요 없다. `MOM-0904`의 token bridge는 되돌리지 않는다.
- **write는 데이터 호환성이 확인되지 않으면 되돌릴 수 없다.** 전략 문서의 6항목을 2단계
  게이트에서 항목별로 확인해 원장에 기록한다. 확인되지 않은 항목이 있으면 writer rollback
  가능하다고 적지 않는다.
- G1이 열린 채 2단계를 실행하면 outbox에 쌓인 이벤트는 롤백해도 사라지지 않는다. 되돌린 뒤
  레거시가 다시 인라인 투영하므로, 나중에 그 이벤트를 소비할 때 같은 문서를 두 경로가 갱신한다.

### 7.3 결정권

- **되돌림의 트리거는 전환을 실행한 사람이 당긴다.** 6.1의 기준에 걸리면 상의 없이 되돌린다.
  되돌림은 되돌릴 수 있는 행위이고, 판단을 미루는 동안의 손실이 더 크다.
- 되돌린 뒤에 원인 분석과 재시도 여부를 상의한다.

### 7.4 FE 배포·롤백 경로

`momens-fe`는 [Cloudflare Workers의 GitHub 연동](https://developers.cloudflare.com/workers/ci-cd/builds/git-integration/github-integration/)으로
배포된다. `wrangler.jsonc`의 Worker 이름은 `momens-fe`, custom domain은 `app.momens.works`, 정적
산출물은 `dist`다. FE TL이 실행하고 컷오버 실행자가 함께 확인한다.

**배포**

1. 컷오버 변경을 `momens-fe`의 `main`에 merge한다.
2. 해당 commit의 GitHub check `Workers Builds: momens-fe`가 `success`가 될 때까지 기다린다. 최근
   기준선 commit `c230e826`에서 이 check가 성공한 것을 확인했다.
3. **5분 안에 success가 되지 않거나 failure/cancelled면 단계에 진입하지 않는다.** 성공하면
   `https://app.momens.works`를 새로 열어 6.1의 해당 단계 smoke를 시작한다.

**긴급 롤백**

1. Cloudflare의 [dashboard rollback 절차](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/)에
   따라 **Workers & Pages → momens-fe → Deployments**로 이동한다.
2. 컷오버 직전의 known-good deployment 오른쪽 메뉴에서 **Rollback**을 실행한다.
3. active deployment가 직전 version으로 바뀌고 `app.momens.works`에서 해당 단계의 로그인과 첫
   보호 API가 성공하는지 확인한다. **5분 안에 확인되지 않으면 FE TL이 Cloudflare 상태와 route를
   직접 점검하고 컷오버를 중단한 상태로 유지한다.**
4. Git의 컷오버 commit도 revert해 다음 `main` push가 실패한 설정을 다시 배포하지 않게 한다.

실행 전에 Cloudflare dashboard 접근 권한, 기준선 deployment의 version ID, 컷오버 commit SHA를
`MOM-0911`에 남긴다. 이 셋이 없으면 배포를 시작하지 않는다.
