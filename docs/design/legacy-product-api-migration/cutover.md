# 웹·MCP 컷오버 실행과 rollback runbook

상태: 인증 1단계 완료(MOM-0906). Product API·OAuth/MCP UI·MCP 공동 전환은 실행·검증 대기.

현재 결정은 MOM-0953·0967·0992를 따른다. MOM-0953 완료는 정책 결정 완료이며 G2 해소가 아니다.
1단계 실행 이력은 MOM-0906·0911에 있다. 표면별 증거는 [이관 원장](ledger.md),
원칙은 [이관 전략](strategy.md), MCP 경계는
[ADR-0023](../../adr/0023-mcp-oauth-migration-boundary-and-cutover.md)를 따른다.
아래 운영 환경 설명은 기존 확인 기록이다. 실행 직전 실제 배포 SHA·설정·접근 수단을 다시 확인한다.

## 1. 전환 단위

Product capability별 혼합 전환이나 OAuth/MCP UI의 legacy base 격리를 도입하지 않는다.
실사용 기능과 필요한 projection을 준비한 뒤 MOM-0967·0992를 같은 실행 창에서 수행한다.
두 작업의 전체 완료를 서로의 선행으로 두지 않는다.

인계 중 task 쓰기를 잠시 중단하고 legacy writer 차단·drain 뒤 신규 writer를 연다.
두 서버 task writer 공존 예외는 없다. 일시적인 인증 실패·전원 재로그인은 허용하지만 명시적
로그아웃 뒤 인증이 남는 것은 허용하지 않는다. 기존 MCP grant/client/token은 이전하지 않고 재연결한다.

## 2. 전환 스위치

기존 ingress는 `/api/*`를 신규 서버, `/`를 레거시로 보낸다. FE base 변경만으로 직접 호출이나
기존 MCP 연결이 차단되지는 않는다. legacy route 차단과 `/.well-known` 라우팅은 MOM-0992에서
별도로 검증하고 적용한다.

| 스위치 | 역할 |
| --- | --- |
| `VITE_AUTH_LOGIN_URL`, `VITE_AUTH_LOGOUT_URL` | 이미 전환한 인증 진입점. 별도 설정 유지 |
| `VITE_API_BASE_URL` | Product API와 신규 OAuth/MCP UI 연결. `/auth/me`도 `/api/me` 계약으로 변경 |
| Google callback 허용 목록 | 신규·레거시 callback 병행 등록 유지 |
| source provider callback 허용 목록 | GitHub·Slack·Notion·Figma의 신규·레거시 callback 병행 등록 |

`VITE_LEGACY_API_BASE_URL`은 새로 도입하지 않는다. 기존 배포에 있다면 사용처를 확인하고
MOM-0967에서 제거 범위를 정한다.

운영 값의 정본은 **Cloudflare 빌드 변수**다. 같은 이름의 값은 `.env.production`보다 우선하며,
없는 값을 지우면 `src/api/config.ts`의 파생 규칙이 적용될 수 있다. 새 변수는 Cloudflare에 먼저
등록하고 저장소 값을 제거하기 전 대시보드의 존재 여부를 확인한다. Vite 값은 빌드 시 번들에
들어가므로 변수 변경 후 재빌드·재배포하고 실제 번들의 URL을 확인한다.
변경 시각·이전/이후 값과 배포 증거는 실행 작업에 남긴다(credential은 기록하지 않는다).

## 3. 실행 직전 확인

- 대상 Deployment의 pod·readiness와 실제 보호 경로 응답을 확인한다. 과거 `/api/health`의
  비인증 응답은 401이었으며, 503이 아닌지 확인한 기록만으로 이번 배포의 readiness를 대신하지 않는다.
- 두 서버 세션 bridge를 유지하는 동안 JWT 서명 키 동일성(MOM-0873)을 확인한다.
- Google 신규·레거시 callback 병행 등록과 웹 client ID/secret, 신규 로그인→쿠키 발급을 검증한다.
  모바일 audiences 확인은 웹 callback 검증과 별개다.
- 신규 logout은 access/refresh/session 세 쿠키를 올바른 Path에서 만료하고 refresh를 폐기해야 한다.
- [ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md)·
  [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)의 bridge는 별도
  제거 조건 확인 전 유지한다. 1단계 인증 전환이 자동 refresh 도입을 뜻하지 않는다.
- FE TL과 일정을 맞추고 Cloudflare 접근 권한·known-good deployment version·전환 commit을 기록한다.

## 4. 1단계 경계

인증 1단계는 로그인·로그아웃 URL만 전환했다. Product API 경로 변경과 OAuth/MCP UI 연결은
2단계에 함께 배포한다. 완료된 1단계의 재실행 일지는 MOM-0906·0911에서 확인하며,
인증 rollback 경로는 아래 7.1절을 유지한다.

## 5. 2단계 게이트

실제 증거가 모이기 전에는 실행하지 않는다. MOM-0967이 G2 증거를 종합하고 MOM-0992가 MCP·Slack
차단 및 drain 증거를 제공한다. 구현 완료만으로 아래 gate를 닫지 않는다.

### G1 — retrieval 투영 공백 (`MOM-0898`, `MOM-0956`, `MOM-0957`)

신규 서버는 변경과 outbox를 기록하고 worker가 projection한다. 코드 존재와 prod에서 실제 소비·
투영이 되는 것은 다르다. task·memory뿐 아니라 이관 확정된 decision·blocker도 각각
MOM-1020·1021 및 MOM-1022에서 검증한다.

**이 실패는 조용하다.** write는 성공하므로 5xx도 401도 나지 않고 6절의 관측 창에서 잡히지
않는다. `MOM-0898` 완료만으로 이 게이트를 닫지 않는다. 공통 consumer와 aggregate별 projector는
다음 체인 전체를 통과해야 한다.

1. `MOM-0898` — 공통 outbox polling·claim, offset, retry와 DLQ 또는 동등한 실패 격리
2. `MOM-0956` — `task.created`·`task.updated`·`task.deleted`의 최신 상태 hydrate·삭제
   처리와 task별 prod E2E 투영 확인. 삭제 뒤 오래된 생성·수정 이벤트 재처리에서도 문서가
   복원되지 않아야 한다([task consumer 계약](../module-map.md#outbox)).
3. `MOM-0957` — memory event hydrate·projector와 memory별 prod E2E 투영 확인
4. 검증 event가 처리 완료 상태가 되고 consumer offset이 전진함
5. retry 대기나 DLQ 또는 동등한 실패 격리 저장소에 검증 event가 남지 않음
6. retrieval 조회에서 task·memory 변경 결과를 확인함

시계열 수집이 준비되지 않은 환경에서는 lag graph만으로 판정하지 않는다. `MOM-0898`과 각 projector
작업이 정한 조회 수단으로 event 처리 상태·offset·실패 잔여를 직접 확인하고, task와 memory의
증거를 각각 `MOM-0956`·`MOM-0957`에 남긴 뒤 2단계를 연다.
task 이벤트 세 종류의 consumer 지원과 재처리를 확인한 뒤 운영 task writer를 확대·전환한다.
미지원 이벤트가 먼저 쌓이면 처리 완료로 건너뛰지 않고 소비 지원 뒤 재시도해야 한다.

### G2 — 단일 task writer 인계 (MOM-0953·0967·0992)

1. 실제 운영 writer와 배포 SHA를 확인한다. legacy REST·MCP·Slack action, 신규 웹·모바일·Signal·
   MCP·댓글·Minsu background의 활성화/중단 목록을 모두 기록한다. 이미 공존한다면 먼저 기록하고 재평가한다.
2. legacy REST task·댓글 쓰기의 직접 접근과 우회 경로를 막는다(MOM-0967).
3. `/mcp` 직접 접근과 기존 연결의 쓰기를 막고 진행 중 tool/transaction을 확인한다(MOM-0992).
   신규 token 발급 중단만으로 기존 연결 종료를 판단하지 않는다.
4. `MINSU_SLACK_ACTIONS_ENABLED=false`를 모든 legacy replica에 반영하고 이미 시작한 action
   goroutine 종료를 확인한다. 고정 대기나 HTTP drain만으로 대신하지 않는다. Slack 답변 기능은
   유지하고 전체 runtime 종료는 MOM-1023에서 처리한다.
5. 진행 중 legacy 쓰기가 끝난 증거를 남긴 뒤 신규 writer를 연다. 실패하면 양쪽 쓰기를 닫고 복구한다.

Minsu background를 활성화하면 MOM-0823의 `task.draft_generated` 소비도 확인한다.

### G3 — source provider 운영 배선 (MOM-0954)

자격 증명·state secret·token key·redirect/success redirect URI와 Figma webhook 설정을
[운영 준비 대장](../../prod-readiness-ledger.md)에서 확인한다. 미설정 시 기동 성공만으로 연결 성공을
보장하지 못한다. 신규 callback은 `/api/source-connections/oauth/callback`이며 legacy에는 `/api`가
없으므로 네 provider 콘솔에 두 callback을 병행 등록한다. 완료 후 FE redirect까지 검증한다.

### G4 — 신규 OAuth/MCP UI와 canonical (MOM-0967·0992)

신규 OAuth interaction 조회·승인·거절, grant 조회·폐기와 `/api/mcp`를 함께 검증한다.
기존 legacy base 격리 계획은 폐기했다. metadata/registration/authorize/token/transport/tools와
대표 client 재연결·재동의, 반복 갱신·만료 이후 연속 사용·재시작을 확인한다(MOM-1008 근거 포함).
`/.well-known`은 dev 라우팅부터 확인한 뒤 prod에 반영한다. bridge 제거는 별도 조건을 확인한다.

### 함께 확인할 것

- MOM-0883의 배포 후 write 검증 방침과 MOM-0968의 최소 장애 알림.
- [전략의 rollback 호환성 6항목](strategy.md#롤백). MOM-0953의 정적 조사와 구분해 MOM-0967에서
  실제 왕복 검증 증거를 남긴다.
- H022 fallback은 MOM-0967에서 제거하고 H023 snapshot만 사용한다. snapshot 정상·404·권한 오류에서
  개별 legacy API 재호출이 없는지 검증한다. 신규 H022 서버 API는 유지한다.

### 2단계 실행 순서

1. G1~G4와 위 조건, 실제 서버·Cloudflare 설정을 확인하고 MOM-0967·0992의 준비 완료를 맞춘다.
2. 검증 workspace·smoke 데이터 ID·실행 창과 쓰기 중단 범위를 기록한다.
3. legacy REST·MCP 접근을 차단하고 Slack action을 끈 뒤 요청·transaction·goroutine drain을 확인한다.
4. 신규 writer·projection을 열고 Product API·OAuth/MCP UI·MCP를 전환한다.
   FE는 신규 `/api` base로 재빌드·재배포하고 `/auth/me`를 `/api/me` 계약에 맞춘다.
5. 실제 bundle URL·canonical·legacy 쓰기 차단을 확인하고 아래 smoke와 관측을 수행한다.
6. 운영 SHA·설정 반영·차단 응답·마지막 legacy/첫 신규 write·검증 ID를 실행 작업에 기록한다.

## 6. 관측

시계열 수집·로그 집계가 없는 환경에서는 pod와 ingress 액세스 로그를 직접 확인하고 즉시 보존한다.
운영 관측 준비는 MOM-0834·0968, 전환기 코드 제거에 필요한 관찰 기간은 MOM-0875가 소유한다.
아래 단기 판정 창을 장기 사용량 관측이나 projection 검증의 대체로 쓰지 않는다.

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
사용할 confirmed memory 두 건(H093)의 ID를 준비해 `MOM-0967`·`MOM-0992`에 기록한다. 상태 전이가 끝난 레코드를
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

snapshot 오류에서 개별 legacy 목록 API로 fallback하지 않아야 한다(MOM-0967).
MCP·Slack 차단과 신규 쓰기 후 projection도 함께 확인한다.

집중 관측을 통과한 뒤 같은 날 안에 두 사람이 필수 smoke를 한 번 더 실행한다. 로그가 보존되지
않으므로 실행 시각·사용한 workspace·각 항목 결과·관련 로그를 그 자리에서 갈무리해 `MOM-0967`·`MOM-0992`에
남긴다.

## 7. 롤백

FE 배포 복구와 writer 복귀는 다르다. FE env 복구만으로 모바일·MCP·Minsu background 쓰기는
중단되지 않는다. 아래 7.2의 인계 조건을 충족하지 못하면 쓰기를 닫은 채 복구한다.

### 7.1 1단계

Cloudflare에서 직전 FE deployment로 롤백해 로그인과 로그아웃 요청 대상을 함께 레거시 상태로
되돌린다.

- 사용자는 레거시 로그인으로 `session_token`을 다시 발급받는다. 신규 서버는 ADR-0017에 따라 그
  쿠키를 계속 수용한다. 기존 세션의 연속성은 보장하지 않으며 필요하면 다시 로그인한다.
- **레거시의 신규 access token 수용은 되돌리지 않는다.** MCP 재인증의 consent·grant API가
  신규 `access_token`을 계속 받아야 하고, 이 변경은 양방향으로 안전하다.
- Google Cloud 콘솔의 두 callback URI를 모두 유지한다. 레거시 URI를 지우지 않았으므로 FE를
  되돌린 뒤 레거시 로그인이 다시 성립한다.
- `users`에 남은 신규 identity 행은 그대로 둔다. ADR-0016 예외가 두 writer 공존을 이미 허용해
  보상 절차가 필요 없다.

**1단계는 writer rollback이 필요 없다. FE를 되돌린 뒤의 재로그인은 허용된 롤백 비용이다.**

### 7.2 2단계

- 신규 HTTP·모바일·Signal·MCP·댓글·Minsu background 쓰기를 먼저 막고 진행 중 처리를 확인한다.
- 신규 쓰기가 한 건도 없다는 증거가 있으면 기존 경로 복구를 검토한다. 쓰기가 있었거나 불명확하면
  호환성 6항목의 실행 증거와 미처리 outbox 처리 계획이 필요하다.
- 투영 담당자가 진행 중 처리 종료, 미처리·재시도·보류 이벤트 처리/재개 방식을 확인한 뒤 legacy
  인라인 projection을 재개한다. 두 경로를 겹치지 않고 원본·소비 기록을 지우지 않는다.
  최대 event ID만으로 처리 완료를 판단하지 않는다.
- FE는 known-good deployment와 빌드 변수로 복구한다. provider callback 병행 등록과 token bridge를
  유지한다. FE 복구가 기존 MCP 세션 복구를 뜻하지 않으므로 재연결·legacy route 재개는 MOM-0992와 확인한다.
- 호환성이나 투영 수렴을 입증하지 못하면 task 쓰기 중단 상태에서 신규 경로 복구를 우선한다.
  legacy writer를 무조건 재개하지 않는다.

### 7.3 결정권

- **되돌림의 트리거는 전환을 실행한 사람이 당긴다.** 6.1의 기준에 걸리면 상의 없이 되돌린다.
  writer는 7.2의 안전한 인계 조건을 확인하며, 불충족 시 쓰기를 중단한 채 복구한다.
- 되돌린 뒤에 원인 분석과 재시도 여부를 상의한다.

### 7.4 FE 배포·롤백 경로

`momens-fe`는 [Cloudflare Workers의 GitHub 연동](https://developers.cloudflare.com/workers/ci-cd/builds/git-integration/github-integration/)으로
배포된다. `wrangler.jsonc`의 Worker 이름은 `momens-fe`, custom domain은 `app.momens.works`, 정적
산출물은 `dist`다. FE TL이 실행하고 컷오버 실행자가 함께 확인한다.

**배포**

1. 컷오버 변경을 `momens-fe`의 `main`에 merge한다.
2. 해당 commit의 GitHub check `Workers Builds: momens-fe`가 `success`가 될 때까지 기다린다. 해당 실행의 commit과 deployment를 대조한다.
3. **5분 안에 success가 되지 않거나 failure/cancelled면 단계에 진입하지 않는다.** 성공하면
   `https://app.momens.works`를 새로 열어 6.1의 해당 단계 smoke를 시작한다.

**긴급 롤백**

1. Cloudflare의 [dashboard rollback 절차](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/)에
   따라 **Workers & Pages → momens-fe → Deployments**로 이동한다.
2. 컷오버 직전의 known-good deployment 오른쪽 메뉴에서 **Rollback**을 실행한다.
3. active deployment가 직전 version으로 바뀌고 `app.momens.works`에서 해당 단계의 로그인과 첫
   보호 API가 성공하는지 확인한다. **5분 안에 확인되지 않으면 FE TL이 Cloudflare 상태와 route를
   직접 점검하고 컷오버를 중단한 상태로 유지한다.**
4. **다음 `main` push 전에 Cloudflare 빌드 변수를 known-good deployment와 같은 값으로 되돌린다.**
   컷오버에서 새로 등록한 변수는 known-good 상태에 없었다면 제거하고, 값이 바뀐 변수는 이전 값으로
   복구한다. 특히 `VITE_AUTH_LOGIN_URL`, `VITE_AUTH_LOGOUT_URL`, `VITE_API_BASE_URL`,
   기존 배포에 있던 legacy base 변수의 존재 여부와 값을 함께 확인한다. 배포된 번들은 이미 구워진 값을
   사용하므로 이 변경만으로 active deployment가 바뀌지는 않는다.
5. Git의 컷오버 commit을 revert해 `main`에 push한다. 이 push가 새 빌드를 시작하므로, 4번의 Cloudflare
   빌드 변수 복구가 끝나기 전에는 push하지 않는다.
6. `Workers Builds: momens-fe`가 성공하고 새 deployment가 active가 될 때까지 기다린다. 이후
   `app.momens.works`에서 로그인·로그아웃·첫 보호 API를 확인하고, 실제 번들에 포함된 URL을 검증한다.
   빌드가 실패하거나 5분 안에 확인되지 않으면 추가 push를 하지 않고 FE TL이 Cloudflare 상태를
   점검한다.

실행 전에 Cloudflare dashboard 접근 권한, 기준선 deployment의 version ID, 컷오버 commit SHA를
`MOM-0967`·`MOM-0992`에 남긴다. 이 셋이 없으면 배포를 시작하지 않는다.
