# 레거시 Product API 이관 원장

상태: Draft

조사일: 2026-08-14

레거시 기준선: `Momens-Works/momens-api@71bbd07614fd2aef4dec726bafdf86c1bd097ba6`

관련 작업: `MOM-0848`

## 목적과 읽는 법

이 원장은 [레거시 Product API 이관 전략](strategy.md)의 상태 원장이다.
레거시의 HTTP route declaration 96개와 HTTP 밖의 실행 진입점을 코드 기준으로 추적한다.

HTTP 행은 `H001`부터, 비-HTTP 행은 `N001`부터 식별한다. 각 행의 필수 필드는 다음 세 곳을
합쳐 읽는다.

1. **진입점 행**: surface, legacy entry point, handler, 읽기/쓰기, 상태와 행별 예외
2. **trace profile**: handler 이후 service/repository/model/migration/test와 target
3. **전환 profile**: contract, auth/RBAC, writer, projection/external, prod/client gate,
   rollback, task/PR

행에서 profile을 참조하는 것은 필드 생략이 아니다. 같은 capability의 반복 정보를 한 profile에
고정해 route 사이에서 서로 다른 판단이 섞이지 않게 한다. 행의 메모가 profile과 다르면 행의 메모를
우선한다.

상태는 전략의 `traced`, `contract_locked`, `implemented`, `cutover_ready`, `cutover`, `retired`만
사용한다. `implemented`는 신규 코드가 있다는 뜻일 뿐 운영 트래픽이 전환됐다는 뜻이 아니다.

모든 행은 별도 표기가 없으면 다음 공통 값을 가진다.

| 필드 | 공통 값 |
| --- | --- |
| legacy baseline | `71bbd07614fd2aef4dec726bafdf86c1bd097ba6` |
| trace task | `MOM-0848` |
| implementation task/PR | 각 행의 `구현` 표기를 따른다. 표기가 없으면 미생성. H020·H022는 `MOM-0851` |
| legacy entry point traffic owner | `momens-api` |

## 기준선과 전수성 검증

조사 시작 시 레거시 저장소에서 다음을 실행했다. fetch 전후 `origin/main` SHA는 같았고 작업 트리는
깨끗했다.

```bash
git -C ../momens-api fetch origin main
git -C ../momens-api rev-parse origin/main
# 71bbd07614fd2aef4dec726bafdf86c1bd097ba6

rg -n '\.(GET|POST|PATCH|DELETE|PUT|Any)\(' \
  ../momens-api/internal/bootstrap/router.go
# 96 declarations
```

96개는 조건부 등록을 포함한 **소스 선언 수**다. `r.Any("/mcp", ...)`는 선언 하나로 세며 Gin이
런타임에 여러 HTTP method로 확장하는 수는 중복해서 세지 않는다.

| surface | 선언 수 |
| --- | ---: |
| Product JSON API | 75 |
| Product 인증 | 5 |
| OAuth protocol·grant 관리 | 12 |
| MCP transport | 1 |
| 외부 provider callback | 1 |
| Slack webhook | 1 |
| 운영 표면 | 1 |
| **합계** | **96** |

HTTP 밖 표면은 다음 방법을 함께 사용했다.

```bash
rg -n 'mcp\.AddTool' ../momens-api/internal/mcpserver
rg -n 'go func|time\.NewTicker|RunMigrations|BackfillSearchTokens' \
  ../momens-api --glob '*.go' --glob '!**/*_test.go'
rg --files ../momens-api/cmd
```

그 결과 MCP tool 11개, startup migration runner, retrieval backfill·embedding loop, Slack 비동기
처리, HTTP server lifecycle, 오프라인 CLI 3개를 확인했다. 별도 cron/scheduler 등록은 없었다.

현재 HTTP 항목은 `implemented` 46개, `traced` 50개다. 비-HTTP·tool 항목 19개는 모두
`traced` 상태다. `cutover_ready` 이상인 항목은 아직 없다.

## 공통 전환 규칙

- Product JSON API의 target path는 `/api`, handler version은 `1`이며 레거시 root path alias는
  만들지 않는다.
- Product JSON API의 **성공 응답은 레거시 body shape를 보존하고, 에러 응답은 전면 Standard
  모드**를 쓴다([API 응답과 에러 코드](../../spec/api-response-error-codes.md)의 표준 형식·코드).
  FE가 서버 기준을 따르기로 합의했다(2026-08-18, `MOM-0856`). 초기 합의는 H020·H022 한정이었으나
  (`MOM-0851`) 웹 이관 endpoint 전체로 확대됐다. status와 성공 body shape는 characterization
  test로 고정한다.
  - 워크스페이스 리소스의 공통 매핑은 미존재 404 `WORKSPACE_NOT_FOUND`, 비멤버 403
    `AUTH_FORBIDDEN`이다. 레거시가 둘을 구분하지 않고 403으로 뭉개는 동작은 보존하지 않는다.
    레거시가 서버 오류까지 403으로 매핑하는 경우도 보존하지 않고 500으로 낸다.
- 레거시 보호 route는 `session_token` 쿠키 JWT를 사용한다. 신규 웹은
  `access_token`·`refresh_token` HttpOnly 쿠키를 사용한다. 전환기에는 신규 서버가 레거시
  `session_token`을 한시 수용하고([ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md)),
  웹 컷오버 이후에는 레거시가 신규 `access_token`을 수용한다
  ([ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)).
- 모든 Product JSON route에는 FE의 path·`API-Version: 1`·세션 전환이 client gate로 걸린다.
- read-only route의 기본 rollback은 routing rollback이다. write route는 신규 데이터가 레거시
  schema·enum·relation·projection과 호환된다는 증거가 있기 전까지 writer rollback을 보장하지 않는다.
- aggregate별 writer는 한 시점에 하나만 둔다. REST, MCP tool, Slack action, webhook,
  background runtime을 함께 계산한다.
- 자동 release gate는 [prod 운영 준비 대장](../../prod-readiness-ledger.md)의 prod 필수 설정만
  검사한다. 스키마·writer·projection은 aggregate별 `cutover_ready` 증거다.

## 웹 FE 사용 실태

기준선: `momens-fe@d76a2d5`. 모든 **XHR/fetch** 호출이 `src/api/client.ts` 한 곳을 거치며 다른
`fetch` 호출처는 없다. 아래는 그 파일의 메서드별 호출처를 센 결과다.

단, **브라우저 내비게이션과 provider 리다이렉트는 `client.ts` 밖이므로 이 규칙으로 판정하지
않는다.** H014(`window.location.assign(loginUrl)`, `src/app/App.tsx:320`·
`src/components/auth/ApiAuthGate.tsx:45`)와 H015·H082(FE가 호출하지 않고 provider가 되돌아오는
콜백 목적지)가 여기 해당하며, 셋 다 실사용이다. 클라이언트 메서드 부재를 미호출 근거로 쓸 때는
이 세 종류를 먼저 배제한다.

`snapshot 폴백 전용`은 `src/api/workspaceSnapshot.ts`의 `loadWorkspaceSnapshotLegacy`에서만
호출된다는 뜻이다. 이 폴백은 snapshot이 404일 때만 동작하고 "모든 배포 API가 snapshot을 서빙하면
삭제"라는 주석이 달려 있다. 즉 신규 서버가 H023을 제공하면 이 행들의 웹 소비자는 사라진다.

| 분류 | entry |
| --- | --- |
| 실사용 | H009~H011, H014~H021, H023, H024, H027, H029~H032, H034~H037, H041, H046, H050, H052, H060, H061, H062, H063, H064, H065, H067, H068, H069, H071, H072, H082, H084, H085, H086, H088, H093, H096 |
| snapshot 폴백 전용 | H022, H038, H039, H040, H042, H044, H051, H053 |
| 웹 미호출 | H025, H026, H028, H033, H043, H045, H047, H048, H049, H054~H059, H066, H070, H073~H081, H083, H087, H089, H090, H091, H092, H094, H095 |

H001~H008·H012·H013은 웹 FE가 직접 호출하는 대상이 아니다(health, OAuth 서버 메타데이터·토큰,
MCP transport, Slack webhook). 위 표는 나머지 H009~H011과 H014~H096을 전수 분류한 것이며
86개 entry가 중복 없이 모두 들어 있다.

폴백 전용 중 H040(`GET /workspaces/:id/source-connections`)은 폴백 안에서 호출되지만 결과가
구조 분해되지 않아 **버려진다**(`workspaceSnapshot.ts`의 `Promise.all`이 promise 6개 중 5개만
받는다). 사실상 소비자가 없다.

이 결과가 뒤집는 사전 가정:

- **H025·H026(워크스페이스 온보딩 상태 조회·수정)은 웹이 호출하지 않는다.** FE에 해당 클라이언트
  메서드가 없다. 안드로이드도 호출하지 않는다 — 코치마크 완료 여부를 기기 로컬 DataStore의 불리언
  하나로만 저장한다(`momens-android@fe8494f`의
  `core/local/onboarding/OnboardingManagerImpl.kt`). **`MOM-0863` 범위에서 제외하기로 확정했다**
  (PR #156 논의).

  이관 대상이 아니라 retire 후보로 둔다. 근거는 계약이 일반적이지 않다는 점이다.
  `PatchOnboarding`의 요청 body는 `welcome_dismissed`·`setup_dismissed` 두 불리언으로 하드코딩돼
  있고(`workspace/handler.go:464`) 서비스도 `welcome_dismissed_at`·`setup_dismissed_at` 고정 키에
  시각을 박는다(`workspace/service.go:557`). 키-값 저장소가 아니라 이미 사라진 웹 배너 2종 전용
  계약이다. 일반적인 것은 `workspace_members.onboarding_state` JSONB 컬럼뿐이고, 그 컬럼은
  endpoint 없이 Flyway 한 줄로 언제든 추가할 수 있다.

  계정 단위 온보딩 요구가 실제로 나오면 저장 단위(워크스페이스 멤버 → 사용자)와 요청 계약을 모두
  새로 설계해야 하므로 미리 이관해 두는 이득이 없다. 레거시 컬럼과 기존 dismiss 기록은 prod에
  남으므로 되살릴 길은 닫히지 않는다.
- **H043(`POST /workspaces/:id/memories`)과 H087·H090~H092·H094도 웹 미호출이다.** `MOM-0869`의
  범위 중 실제로 쓰이는 것은 H084·H085·H086·H088·H093이다. H087은 후보 상태 모델을 닫으려고 함께
  이관했다(`MOM-0869`).

  나머지 다섯은 `MOM-0901`에서 웹 밖까지 전수로 확인했다. 기준선은 `momens-api@71bbd07`·
  `momens-fe@d76a2d5`이고, MCP 도구 목록(`mcpserver/tools.go`의 11개에 memory 도구 없음), Slack
  bot(매치가 전부 주석 산문), 레거시 내부 호출(memory `Service`의 write 를 부르는 곳은 HTTP 핸들러
  뿐), `seed-demo` CLI(DB 직접 INSERT), `momens-worker`(`confirmed_memories` 쓰기 0건),
  `momens-android`, `e2e`, 모바일 계약, `teams` 제품 문서 모두 호출처가 없다. `momens-fe`는
  `git log -S`로 이력까지 봤고 해당 클라이언트 메서드가 존재한 적이 없다(지운 것이 아니다).

  그 결과 **다섯 모두 retire 후보로 확정했다**(`MOM-0901`). H090~H092·H094는 `momens-api@05a0c60`
  일괄 커밋에서 함께 들어왔고 테스트가 하나도 없다. H043은 `momens-api@d227b4a`로 따로 추가돼
  통합테스트 3건과 입력 검증을 갖췄지만 소비자가 없기는 마찬가지고, 기존 클라이언트가 없어 레거시
  계약을 보존할 이유도 없다. 사람이 메모리를 직접 추가하는 요구가 실제로 나오면 그때 신규 서버에서
  새로 설계하는 편이 낫다.

  다섯 다 되돌릴 길은 닫히지 않는다. 레거시 endpoint와 데이터는 prod에 그대로 남는다.
- **H027(멤버 목록)과 H072(태스크 컨텍스트)는 폴백 전용이 아니다.** 각각 워크스페이스 설정 화면과
  태스크 상세에서 직접 호출한다.
- **H081(sync-states)은 호출처가 하나도 없다.**

원장 표의 행 메모에는 **이관 티켓 범위와 어긋나는 것만** `웹 미호출`을 표기했다(H025·H026·H043·
H081). 나머지 미호출 entry는 위 분류표가 단일 출처다.

blocker·decision 관련 endpoint(H054, H055, H059, H066, H073~H075)와 minsu query(H045)는 웹이 호출하지
않으므로 웹 컷오버 범위에서 제외한다. 후보·메모리 리뷰 액션, source 온보딩(H041·H082·H096),
MCP grants UI(H035·H036)는 실사용이다.

## Trace profile

경로는 모두 레거시 `momens-api` 루트 기준이며, 표 안의 코드 경로는 공통 `internal/` prefix를
생략한다. `cmd/`, `migrations/`처럼 저장소 루트에 바로 있는 경로는 그대로 쓴다. `H/S/R`은
handler/service/repository를 뜻한다.

| Profile | capability·legacy trace | schema·test | target |
| --- | --- | --- | --- |
| `OP` | `bootstrap/router.go`의 inline health handler, `cmd/api/main.go`, `bootstrap/app.go` | DB 없음; `bootstrap/router_test.go`, `bootstrap/app_test.go` | `app`의 Actuator health |
| `MOA-P` | `mcpauth/handler.go` → `service.go` → `repository.go` → `model.go` | `000019_mcp_oauth.sql`; `mcpauth/handler_test.go`, `service_test.go`, `repository_test.go` | **미결**: `auth` 또는 별도 MCP/OAuth 경계 |
| `MOA-I` | `mcpauth/handler.go`의 interaction 조회·승인·거절 → 같은 service/repository/model | `000019_mcp_oauth.sql`, `users`, `workspace_members`; `mcpauth/*_test.go` | **미결**: MCP/OAuth 경계, 사용자 세션은 `auth`·`user`·`workspace` public API 사용 |
| `MOA-G` | `mcpauth/handler.go`의 grant 목록·폐기 → 같은 service/repository/model | `000019_mcp_oauth.sql`; `mcpauth/*_test.go` | **미결**: MCP/OAuth 경계 |
| `MCP` | `mcpserver/server.go`, `auth.go`, `tools.go`, `milestones.go`, `helpers.go` → `project`·`milestone`·`task`·`workspace` service/repository → `domain/models.go` | `000001_init.sql`, `000006_fe_contract.sql`, `000012_task_updates.sql`, `000017_project_label.sql`, `000019_mcp_oauth.sql`; `mcpserver/server_test.go`, `milestones_test.go` | **미결**: transport/tool 소유 경계. 도메인 write는 `workspace`·`project` public API로 위임 |
| `SLK` | `slackbot/handler.go`, `events.go`, `answerer.go`, `grounded.go`, `action.go` → `minsu/service.go`, `minsu/action/*`, `project`·`task`·`workspace` service/repository | task·retrieval schema; `slackbot/*_test.go`, `minsu/action/*_test.go` | `minsu`가 Slack 표면 흡수, task write는 `project` public API 사용 |
| `AUT` | `auth/handler.go` → `auth/service.go` → `auth/repository.go` → `domain.User`, `platform/auth/jwt.go`, `platform/oauth/google.go` | `000001_init.sql`, `000007_user_job_role.sql`, `000018_refresh_tokens.sql`; `auth/service_integration_test.go`, `platform/auth/jwt_test.go` | 도메인 `auth` 세션과 `user` 신원·프로필, HTTP 표면은 `web`·`mobile`(`MOM-0852`) |
| `USR` | legacy `auth/handler.go`의 `Me`·`UpdateMe` → `auth/service.go` → `auth/repository.go` → `domain.User` | `000001_init.sql`, `000007_user_job_role.sql`; `auth/service_integration_test.go` | 도메인 `user`, 웹 표면 `web`(`MOM-0852`); `/api/me` GET/PATCH 구현 완료 |
| `WSP` | `workspace/handler.go` → `service.go` → `repository.go`, `access/service.go`·`repository.go`, `label/label.go` → workspace 관련 `domain/models.go` | `000001_init.sql`, `000006_fe_contract.sql`, `000009_member_onboarding_state.sql`, `000011_workspace_invitations.sql`; `workspace/*_test.go`, `access/service_integration_test.go` | 도메인 `workspace`, 웹 표면 `web` |
| `SNP` | `snapshot/handler.go` → workspace/project/milestone/task/blocker/memory/relation service·repository | 위 capability의 모든 schema; `snapshot/handler_integration_test.go` | 표면과 합성 로직 모두 `:web`(`MOM-0850`). 응답 계약은 [웹 snapshot 계약](slice-snapshot.md)에서 확정(`MOM-0856`) |
| `PRJ` | `project/handler.go` → `service.go` → `repository.go` → `domain.Project`, `access` | `000001_init.sql`, `000006_fe_contract.sql`, `000008_project_metadata.sql`, `000017_project_label.sql`; `project/handler_test.go`, `service_integration_test.go` | `project` |
| `MIL` | `milestone/handler.go` → `service.go` → `repository.go` → `domain.Milestone`, `access` | `000001_init.sql`, `000006_fe_contract.sql`; `milestone/service_integration_test.go` | `project` |
| `TSK` | `task/handler.go` → `service.go` → `repository.go` → `domain.Task`·`TaskUpdate`, `retrieval/projection.go`·`repository.go` | `000001_init.sql`, `000002_retrieval_projection.sql`, `000006_fe_contract.sql`, `000012_task_updates.sql`, `000013_task_workspace_cascade.sql`; `task/service_integration_test.go`, `retrieval/projection_test.go` | `:project`의 `task` 하위 경계; 웹 계약은 `MOM-0773` 필요 |
| `DEC` | `decision/handler.go` → `service.go` → `repository.go` → `domain.Decision`, `retrieval/projection.go` | `000001_init.sql`, `000002_retrieval_projection.sql`; 전용 test 없음 | `project` |
| `BLK` | `blocker/handler.go` → `service.go` → `repository.go` → `domain.Blocker`, `retrieval/projection.go` | `000001_init.sql`, `000002_retrieval_projection.sql`; 전용 test 없음 | `project` |
| `SRC` | `source/handler.go` → `service.go` → `repository.go`, `oauth.go`, `figma.go` → source 관련 `domain/models.go` | `000002_retrieval_projection.sql`, `000004_source_connections.sql`, `000006_fe_contract.sql`, `000010_source_credentials.sql`, `000015_source_refs_content_hash.sql`; `source/service_integration_test.go`, `oauth_test.go`, `figma_test.go` | `source`; ingest는 `momens-worker` |
| `MEM` | `memory/candidate_handler.go`, `memory/memory_handler.go` → `service.go` → `repository.go` → memory 관련 `domain/models.go`, `retrieval/projection.go` | `000003_memory.sql`, `000006_fe_contract.sql`, `000002_retrieval_projection.sql`; `memory/service_integration_test.go` | `memory` 모듈 신설(`MOM-0860`). read 기반과 후보 리뷰·resolve write 구현 완료(`MOM-0869`). H043·H090~H092·H094 lifecycle write는 미구현 |
| `CTX` | `relation/handler.go` → `service.go` → `repository.go` → `domain.EntityRelation`·`SourceRef` | `000002_retrieval_projection.sql`; 전용 test 없음 | `context`; source-ref 원본 조회는 `source` public API 사용 |
| `MIN` | `minsu/handler.go` → `service.go`, `synthesis.go`, `embedding.go` → `retrieval/client.go`와 LLM adapter | 자체 write schema 없음; `minsu/service_test.go`, `embedding_test.go`, retrieval client tests | `minsu` query 유스케이스. 기존 task draft API DTO와 분리 |

## 전환 profile

| Profile | contract·auth/RBAC | writer·projection/external | gate·rollback·task |
| --- | --- | --- | --- |
| `OP` | 운영 계약. 공개 health | read-only, DB 없음 | 신규 `/actuator/health`는 구현됨. ingress/probe 전환과 routing rollback 필요. `MOM-0848` |
| `MOA-P` | OAuth metadata·등록·인가·token·revoke protocol 계약. endpoint별 client/token 검증 | `oauth_*` writer; OAuth client와 MCP client 외부 의존 | `oauth_*` prod 존재와 target module 미확정. protocol client rollback/runbook 필요. 후속 MCP/OAuth 결정 작업 |
| `MOA-I` | legacy `session_token` 인증 후 interaction user/workspace 검증 | `oauth_interactions`, grant/code writer | 웹 컷오버 시점 동작은 [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)로 해소(레거시가 신규 `access_token` 수용). 2단계에는 `MOM-0906`의 legacy base로 라우팅. writer rollback 미확정. 후속 MCP/OAuth 결정 작업 |
| `MOA-G` | legacy 세션 + workspace membership/grant ownership | `oauth_grants` writer | 웹 컷오버 시점 동작은 [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)로 해소. 2단계에는 `MOM-0906`의 legacy base로 라우팅. grant/token drain·폐기 정책 필요. 후속 MCP/OAuth 결정 작업 |
| `MCP` | MCP Streamable HTTP, OAuth bearer와 tool별 scope | project/milestone/task/comment writer가 REST와 같은 aggregate를 공유 | MCP client 재등록, grant/token 전환, 단일 writer와 rollback 필요. 후속 MCP/OAuth 결정 작업 |
| `SLK` | Slack signature·retry·3초 ack 계약 | retrieval·Vertex·Slack API; action layer가 task writer | signing secret, bot identity, redirect/event URL, 비동기 실패 관측 필요. task writer·projection과 함께 전환. 후속 Slack 표면 작업 |
| `AUT` | 레거시 단일 JWT 대신 확정된 Standard 웹 access+refresh 쿠키 계약. H014~H016은 공개 transport | `users`, `user_identities`, `refresh_tokens`; Google OAuth. 아래 `users` writer 예외 적용 | 신규 경로 구현 완료(`MOM-0640`, `MOM-0641`). FE 로그인과 함께 전환하며 레거시 세션 rollback은 별도 브리지 없이는 불가 |
| `USR` | 신규 `/api/me` Standard 계약. H017~H018은 legacy 세션, target은 cookie/Bearer 공통 인증의 현재 사용자 | `users` writer. 아래 `users` writer 예외 적용 | 구현 완료(`MOM-0632`, `MOM-0635`). legacy wrapper와 field 차이 characterization 및 FE 전환 필요 |
| `WSP` | Product JSON 기본 규칙과 legacy 세션. H019~H021은 인증-only, H022·H025~H027은 member, H024·H028~H034는 admin/owner, H046은 인증 + invitation token·email 일치 | `workspaces`, member, invitation, label sequence writer; invitation email. H019 생성 경로는 `projects`, `project_owners`, `confirmed_memories`에도 함께 쓴다. | `MOM-0845` workspace scope 영향. read는 routing rollback, write는 schema/label/email 호환 확인 전 rollback 미보장 |
| `SNP` | Product JSON 기본 규칙, workspace membership | read-only 합성; 여러 aggregate·relation 조회 | read routing rollback 가능. 응답 필드별 target owner와 N+1/latency 계약을 먼저 고정. `MOM-0848` 후속 |
| `PRJ` | Product JSON 기본 규칙과 legacy 세션. H038·H047은 member, H037·H048~H049는 workspace admin/owner | `projects`, `project_owners`; projection 없음. 워크스페이스 생성(H019)도 `projects`와 `project_owners`에 쓴다. | `MOM-0845`와 legacy 전용 field/owner/progress 정책 확인. read routing rollback, write rollback 미확정 |
| `MIL` | Product JSON 기본 규칙, project workspace membership | `milestones`, `milestone_owners`; projection 없음 | target entity와 read 기반 구현 완료(`MOM-0858`), endpoint는 없음. `milestones`에 `workspace_id`가 없어 조회가 `projects`를 조인하고, 소유 모듈이 `project`인 이유도 이것이다. read routing rollback, write rollback 미확정 |
| `TSK` | Product JSON 기본 규칙, project workspace membership | 현재 legacy REST·MCP·Slack writer가 `tasks`, `task_updates`를 변경. target에는 모바일 생성·수정·체크리스트, Signal 전환, Minsu background draft 반영 writer가 구현되어 같은 `tasks`를 사용하며 task write는 retrieval projection 동반 | `MOM-0773`, 공통 outbox consumer(`MOM-0898`)와 task projector·prod E2E 검증(`MOM-0956`)이 `cutover_ready` gate. target writer의 prod 활성화 증거는 없으며 aggregate 전체를 한 번에 전환. write rollback 미확정 |
| `DEC` | Product JSON 기본 규칙, project workspace membership | `decisions`; write는 retrieval projection 동반 | 전용 legacy test가 없어 characterization 우선. worker decision projector가 write gate |
| `BLK` | Product JSON 기본 규칙과 legacy 세션. H039·H059·H066·H074는 member, H075 삭제는 admin/owner | `blockers`; write는 retrieval projection 동반 | 전용 legacy test가 없어 characterization 우선. worker blocker projector가 write gate |
| `SRC` | Product JSON 또는 provider callback 계약. H040·H076·H081·H096은 member, H041·H077~H080은 admin/owner, H082 callback은 공개 transport + signed state | source connection/credential/sync state/source-ref writer; GitHub·Slack·Notion·Figma | provider redirect URI·secret·webhook·worker 호환 필요. `MOM-0774`가 source_ref 관계 계약에 영향 |
| `MEM` | Product JSON 기본 규칙, workspace membership | candidate/memory/review action writer(`review_actions` 미러 신설, `MOM-0869`); confirmed memory write는 retrieval projection 동반이라 신규는 outbox 이벤트로 남긴다. 워크스페이스 생성(H019)은 `confirmed_memories`에 쓰지만, 레거시가 해당 경로에서 검색 문서를 생성하지 않으므로 이벤트도 남기지 않는다. | 공통 outbox consumer(`MOM-0898`)와 memory projector·prod E2E 검증(`MOM-0957`)이 write gate. read routing rollback, write rollback 미확정 |
| `CTX` | Product JSON 기본 규칙, task workspace membership | `entity_relations`, 일부 `source_refs` writer | `MOM-0774`, source-ref 생산자·relation 호환 확인. read routing rollback, write rollback 미확정 |
| `MIN` | Product JSON 기본 규칙, workspace membership | domain write 없음; retrieval gRPC, Vertex AI | timeout·fallback·permission context·model 설정 contract lock 필요. read routing rollback 가능 |

### `users` writer 한시적 예외

`AUT`와 `USR`의 `users` aggregate는 [ADR-0016](../../adr/0016-user-identity-key-google-sub.md)에 따른
단일 writer 원칙의 한시적 예외다. 현재 writer는 레거시 웹 로그인·프로필 수정의 `momens-api`와 신규
모바일·웹 로그인·`/api/me`의 `momens-server` 두 곳이다. 한 요청을 복제하는 dual-write가 아니라
클라이언트 경로별 writer가 같은 `users` 행에 공존하는 상태다.

- 레거시 H014~H015 로그인과 H018 프로필 수정 트래픽이 중단되고 신규 인증·`/api/me` 전환이
  확인될 때까지 이 예외를 유지한다.
- 전환 중에는 `users.email` UNIQUE가 레거시 read-then-insert 동시성 장치이므로 유지한다.
- `momens-server`가 `users`의 유일한 writer가 된 뒤에만 `MOM-0836`으로 UNIQUE 제거를 실행한다.
- 이 기간의 writer rollback은 레거시가 신규 identity를 이해하지 못하므로 단순 DB rollback이 아니다.
  신규 로그인 트래픽을 중단하고 레거시 세션 경로로 되돌릴 수 있는지 별도 runbook에서 확인한다.

### `workspace_members` 전환 단위

레거시에서 `workspace_members`에 데이터를 쓰는 경로는 다섯 개다. 워크스페이스 생성 시 owner
등록(H019), 즉시 멤버 추가(H028), 역할 변경(H033), 멤버 제거(H034), 초대 수락(H046)이다.
aggregate별 writer를 한 시점에 하나만 두는 원칙에 따라 다섯 경로를 같은 시점에 전환한다.

`MOM-0897`에서 H019를 구현하면서 다섯 경로의 구현을 모두 마쳤다. 초대 기능은 별도의 전환 단위로
관리한다. `workspace_invitations`를 참조하는 endpoint는 H029~H032와 H046이 전부이며, worker와
retrieval에는 해당 테이블을 참조하는 코드가 없다.

### `tasks` target writer 구현과 운영 활성화

`momens-server`에는 모바일 수동 생성·수정·체크리스트 변경, Signal의 convert-to-task, Minsu
background draft 반영이 구현되어 있다. 이 경로들은 prod에서 활성화되면 레거시가 소유한 같은
`tasks` aggregate를 변경한다. 다만 코드 존재는 `implemented` 증거일 뿐 현재 prod writer라는
증거가 아니다.

따라서 `users`의 ADR-0016 예외를 `tasks`에 확장하지 않는다. target writer를 활성화하기 전 prod
schema·routing·실제 client traffic을 확인하고, legacy REST·MCP·Slack writer를 포함한 aggregate
전체를 한 전환 단위로 넘긴다. 두 서버의 task writer를 동시에 활성화해야 한다면 이 원장의 암묵적
예외가 아니라 별도 결정과 rollback 조건을 먼저 기록한다.

## HTTP 진입점 원장

`R`은 read-only, `W`는 상태·쿠키·외부 side effect를 변경, `RW`는 하나의 transport 아래 양쪽이
공존함을 뜻한다. Product JSON 행은 위 공통 전환 규칙을 따라 **성공 body는 레거시 보존, 에러는
Standard 모드**이며, 모두 `MOM-0848`에서 `traced`됐다.

| ID | surface | legacy entry point | handler | profile | mode | status·메모 |
| --- | --- | --- | --- | --- | --- | --- |
| H001 | operational | `GET /health` | inline | `OP` | R | `implemented`: target은 `/actuator/health`, 아직 probe 전환 아님 |
| H002 | OAuth | `GET /.well-known/oauth-authorization-server` | `AuthorizationServerMetadata` | `MOA-P` | R | `traced`; 조건부 등록 |
| H003 | OAuth | `GET /.well-known/oauth-protected-resource` | `ProtectedResourceMetadata` | `MOA-P` | R | `traced`; 조건부 등록 |
| H004 | OAuth | `GET /.well-known/oauth-protected-resource/mcp` | `ProtectedResourceMetadata` | `MOA-P` | R | `traced`; 조건부 등록 |
| H005 | OAuth | `POST /oauth/register` | `Register` | `MOA-P` | W | `traced`; dynamic client registration |
| H006 | OAuth | `GET /oauth/authorize` | `Authorize` | `MOA-P` | W | `traced`; consent interaction 생성·redirect |
| H007 | OAuth | `POST /oauth/token` | `Token` | `MOA-P` | W | `traced`; code 교환·refresh 회전 |
| H008 | OAuth | `POST /oauth/revoke` | `Revoke` | `MOA-P` | W | `traced` |
| H009 | OAuth | `GET /oauth/interactions/:id` | `GetInteraction` | `MOA-I` | R | `traced`; legacy 세션 필요. 웹 컷오버 시 레거시가 신규 `access_token` 쿠키를 수용하고, 2단계에는 `MOM-0906`의 legacy base로 라우팅해 유지한다(`MOM-0871`, [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)) |
| H010 | OAuth | `POST /oauth/interactions/:id/approve` | `Approve` | `MOA-I` | W | `traced`; grant/code 생성. 웹 컷오버 시 레거시가 신규 `access_token` 쿠키를 수용하고, 2단계에는 `MOM-0906`의 legacy base로 라우팅해 유지한다(`MOM-0871`, [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)) |
| H011 | OAuth | `POST /oauth/interactions/:id/deny` | `Deny` | `MOA-I` | W | `traced`; 웹 컷오버 시 레거시가 신규 `access_token` 쿠키를 수용하고, 2단계에는 `MOM-0906`의 legacy base로 라우팅해 유지한다(`MOM-0871`, [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)) |
| H012 | MCP | `ANY /mcp` | `mcpserver.Server` | `MCP` | RW | `traced`; 조건부 등록, 선언 하나로 계산 |
| H013 | webhook | `POST /slack/events` | `slackbot.Events` | `SLK` | RW | `traced`; 조건부 등록, signed webhook |
| H014 | Product auth | `GET /auth/google/login` | `auth.GoogleLogin` | `AUT` | W | `implemented`: target `/api/auth/google/login`, state+PKCE 계약으로 교체 |
| H015 | Product auth | `GET /auth/google/callback` | `auth.GoogleCallback` | `AUT` | W | `implemented`: target `/api/auth/google/callback`, access+refresh 발급 |
| H016 | Product auth | `POST /auth/logout` | `auth.Logout` | `AUT` | W | `implemented`: target `/api/auth/web/logout`; legacy message body는 폐기 합의됨 |
| H017 | Product auth | `GET /auth/me` | `auth.Me` | `USR` | R | `implemented`: target `GET /api/me`; cutover 전 |
| H018 | Product auth | `PATCH /auth/me` | `auth.UpdateMe` | `USR` | W | `implemented`: target `PATCH /api/me`; cutover 전 |
| H019 | Product JSON | `POST /workspaces` | `workspace.Create` | `WSP` | W | `implemented`: target `POST /api/workspaces`. 구현 완료(`MOM-0897`), 전환 전. 워크스페이스 행과 owner 멤버십을 생성하고, 이름이 `Welcome`인 프로젝트 한 개와 메모리 세 건을 같은 트랜잭션에서 저장한다. `project`와 `memory`가 이미 `workspace`를 참조하므로 반대 방향으로 참조하면 순환 의존성이 발생한다. 세 모듈의 저장 순서와 트랜잭션 경계는 새로 추가한 `onboarding` 모듈이 소유한다. 프로젝트 라벨과 메모리 라벨은 `LabelAllocator`에서 발급해 INSERT에 포함한다. 레거시는 라벨이 없는 INSERT에 대해 트리거가 값을 채우지만 신규 서버에는 해당 트리거가 없다. slug를 전달하지 않으면 워크스페이스 이름을 기준으로 생성하고, 이미 사용 중이면 뒤에 번호를 붙여 다음 후보를 찾는다. 한글로만 구성된 이름은 slug에 사용할 수 있는 문자가 남지 않아 `workspace`를 사용하며 레거시와 같은 동작이다. 해당 경로에서 생성한 메모리에는 검색 반영 이벤트를 남기지 않는다. 레거시 `seedWelcome`도 검색 문서를 생성하지 않기 때문이다. `confirmed_memories.created_at`은 JPA Auditing이 채우므로 `confirmed_at`과 밀리초 단위로 차이가 날 수 있다. 응답에 포함되지 않는 값이므로 별도로 일치시키지 않는다. 실패 응답은 공통 전환 규칙에 따라 Standard 형식을 사용하며 HTTP status는 레거시와 같다. 이름이 비어 있으면 400, slug가 형식 또는 예약어 규칙에 맞지 않으면 400, 이미 사용 중이면 409로 응답한다. |
| H020 | Product JSON | `GET /workspaces` | `workspace.List` | `WSP` | R | `implemented`: target `GET /api/workspaces`, [첫 웹 read 슬라이스 계약](slice-workspace-read.md) (`MOM-0850`). 구현 완료(`MOM-0851`), 전환 대상; cutover 전. 선행 게이트 `MOM-0873` 완료: 2026-09-10에 prod 두 서버의 JWT 서명 키를 동일한 새 값으로 갱신·재기동하고, 레거시 `session_token`으로 target 응답 200을 확인했습니다 |
| H021 | Product JSON | `GET /workspaces/slug-available` | `workspace.SlugAvailable` | `WSP` | R | `implemented`: target `GET /api/workspaces/slug-available`. 구현 완료(`MOM-0863`), 전환 전 |
| H022 | Product JSON | `GET /workspaces/:id` | `workspace.Get` | `WSP` | R | `implemented`: target `GET /api/workspaces/{workspaceId}`, [첫 웹 read 슬라이스 계약](slice-workspace-read.md) (`MOM-0850`). 구현 완료(`MOM-0851`), 전환은 제외. 웹 소비자가 snapshot 폴백뿐임이 FE 기준선에서 확인됐다. H023 제공 시 폴백이 삭제되면 웹 소비자가 사라지므로, 전환 여부는 `MOM-0862` 머지 후 판단한다 |
| H023 | Product JSON | `GET /workspaces/:id/snapshot` | `snapshot.Get` | `SNP` | R | `implemented`: target `GET /api/workspaces/{workspaceId}/snapshot`, [웹 snapshot 계약](slice-snapshot.md) (`MOM-0856`). 구현 완료(`MOM-0862`), 전환 전. 웹 read의 유일한 실질 경로 |
| H024 | Product JSON | `PATCH /workspaces/:id` | `workspace.Update` | `WSP` | W | `implemented`: target `PATCH /api/workspaces/{workspaceId}`. 구현 완료(`MOM-0863`), 전환 전. 레거시에서는 미존재와 권한 부족을 모두 403으로 응답했으나, 신규 서버에서는 미존재는 404, 권한 부족은 403으로 구분한다. 레거시는 변경할 값이 없는 요청에도 `updated_at`을 갱신했지만, 신규 서버는 값이 실제로 변경된 경우에만 갱신한다. 웹이 이 endpoint를 신규 서버로 호출하기 시작하면 신규 서버가 `workspaces` 행을 수정하는 반면, 레거시는 H019를 통해 같은 테이블에 행을 생성하므로 H019와 같은 시점에 전환한다 |
| H025 | Product JSON | `GET /workspaces/:id/onboarding` | `workspace.GetOnboarding` | `WSP` | R | `traced`; **웹 미호출**(`MOM-0856`). `MOM-0863` 범위에서 **제외 확정**(PR #156). 이관 대상이 아니라 retire 후보 |
| H026 | Product JSON | `PATCH /workspaces/:id/onboarding` | `workspace.PatchOnboarding` | `WSP` | W | `traced`; **웹 미호출**(`MOM-0856`). `MOM-0863` 범위에서 **제외 확정**(PR #156). 이관 대상이 아니라 retire 후보 |
| H027 | Product JSON | `GET /workspaces/:id/members` | `workspace.ListMembers` | `WSP` | R | `implemented`: target `GET /api/workspaces/{workspaceId}/members`. 구현 완료(`MOM-0864`), 전환 전. 레거시는 쿼리에 정렬 조건이 없어 응답 순서를 보장하지 않습니다. 신규 서버는 [웹 snapshot 계약](slice-snapshot.md) 4.4절에서 H023의 `members`와 함께 확정한 `workspace_members.created_at ASC, user_id ASC`를 정렬 기준으로 사용합니다. `MOM-0895`에서 해당 기준을 적용했습니다. 두 endpoint가 같은 조회 서비스를 사용하므로 동일한 순서를 유지합니다. 정렬은 SQL에서 처리합니다. 사용자 식별자를 애플리케이션에서 비교하면 Java의 `UUID` 비교 결과가 PostgreSQL의 `uuid` 정렬 결과와 달라 확정된 기준을 보장할 수 없기 때문입니다. 레거시에서 워크스페이스 미존재와 비멤버를 모두 403으로 응답하던 동작은 공통 전환 규칙에 따라 각각 404와 403으로 구분한다 |
| H028 | Product JSON | `POST /workspaces/:id/invite` | `workspace.Invite` | `WSP` | W | `implemented`: target `POST /api/workspaces/{workspaceId}/invite`. 구현 완료(`MOM-0865`), 전환 전. 웹에서 호출하지 않는 경로지만 `workspace_members` 전환 단위에 포함되어 함께 이관했다. 레거시는 이 경로의 실패를 모두 403으로 응답하므로 HTTP status는 유지하고 에러 코드만 구분했다. 이메일에 해당하는 사용자가 없으면 `WORKSPACE_INVITEE_NOT_FOUND`, 사용자가 이미 다른 역할로 참여 중이면 `WORKSPACE_MEMBER_ROLE_CONFLICT`로 응답한다. 이메일은 레거시와 동일하게 대소문자까지 정확히 일치할 때만 사용자를 찾는다 |
| H029 | Product JSON | `POST /workspaces/:id/invitations` | `workspace.CreateInvitation` | `WSP` | W | `implemented`: target `POST /api/workspaces/{workspaceId}/invitations`. 구현 완료(`MOM-0865`), 전환 전. 같은 워크스페이스에 같은 이메일로 대기 중인 초대가 있으면 새 행을 생성하지 않고 기존 행을 갱신한다. 해당 이메일의 사용자가 이미 워크스페이스 멤버이면 409 `WORKSPACE_MEMBER_ALREADY_EXISTS`로 응답하며, 이 판정은 레거시와 동일하게 이메일의 대소문자를 구분하지 않는다. 이메일 발송에 실패하면 502 `INVITATION_EMAIL_SEND_FAILED`로 응답하지만 생성하거나 갱신한 초대 행은 유지한다 |
| H030 | Product JSON | `GET /workspaces/:id/invitations` | `workspace.ListInvitations` | `WSP` | R | `implemented`: target `GET /api/workspaces/{workspaceId}/invitations`. 구현 완료(`MOM-0865`), 전환 전. 만료 상태는 DB에 저장하지 않고 조회 시점에 계산한다 |
| H031 | Product JSON | `POST /workspaces/:id/invitations/:invitationId/resend` | `workspace.ResendInvitation` | `WSP` | W | `implemented`: target `POST /api/workspaces/{workspaceId}/invitations/{invitationId}/resend`. 구현 완료(`MOM-0865`), 전환 전. 토큰과 만료 시각을 새로 발급하며, 폐기된 초대도 대기 상태로 되돌린다. 이미 수락된 초대만 409 `INVITATION_ALREADY_ACCEPTED`로 거부한다. 수락 여부를 조건에 포함한 단일 UPDATE 쿼리로 처리해 조회와 갱신 사이에 초대 수락이 완료되는 동시성 문제를 방지한다 |
| H032 | Product JSON | `POST /workspaces/:id/invitations/:invitationId/revoke` | `workspace.RevokeInvitation` | `WSP` | W | `implemented`: target `POST /api/workspaces/{workspaceId}/invitations/{invitationId}/revoke`. 구현 완료(`MOM-0865`), 전환 전. 이미 수락된 초대는 폐기할 수 없으며 409 `INVITATION_ALREADY_ACCEPTED`로 응답한다 |
| H033 | Product JSON | `PATCH /workspaces/:id/members/:userId` | `workspace.UpdateMember` | `WSP` | W | `implemented`: target `PATCH /api/workspaces/{workspaceId}/members/{userId}`. 구현 완료(`MOM-0864`), 전환 전. 레거시에서 403 하나로 처리하던 실패 상황을 네 가지로 구분한다. 워크스페이스가 없으면 404 `WORKSPACE_NOT_FOUND`, 요청자의 권한이 부족하면 403 `AUTH_FORBIDDEN`, 대상 사용자가 멤버가 아니면 404 `WORKSPACE_MEMBER_NOT_FOUND`, 대상이 owner이면 409 `WORKSPACE_OWNER_PROTECTED`를 반환한다. 앞의 두 경우는 공통 전환 규칙에 따른 것이며, 뒤의 두 경우는 이 작업에서 결정했다. `role`이 `admin`이나 `member`가 아니면 레거시와 동일하게 400으로 응답하며, 에러 코드는 `WORKSPACE_INVALID_ROLE`이다 |
| H034 | Product JSON | `DELETE /workspaces/:id/members/:userId` | `workspace.RemoveMember` | `WSP` | W | `implemented`: target `DELETE /api/workspaces/{workspaceId}/members/{userId}`. 구현 완료(`MOM-0864`), 전환 전. 실패 상황은 H033과 같은 기준으로 구분하며, 요청자가 자기 자신을 제거하려는 경우에는 409 `WORKSPACE_SELF_REMOVAL_NOT_ALLOWED`를 반환한다. 레거시와 동일하게 자기 자신인지 먼저 확인한 뒤 대상 멤버를 조회한다. 따라서 존재하지 않는 사용자 ID라도 요청자 자신의 ID를 전달하면 두 서버 모두 자기 자신을 제거하려는 요청으로 판정한다 |
| H035 | OAuth | `GET /workspaces/:id/mcp-grants` | `mcpauth.ListGrants` | `MOA-G` | R | `traced`; 조건부 등록. 웹 컷오버 시 레거시가 신규 `access_token` 쿠키를 수용하고, 2단계에는 `MOM-0906`의 legacy base로 라우팅해 유지한다(`MOM-0871`, [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)) |
| H036 | OAuth | `DELETE /workspaces/:id/mcp-grants/:grantId` | `mcpauth.RevokeGrant` | `MOA-G` | W | `traced`; 조건부 등록. 웹 컷오버 시 레거시가 신규 `access_token` 쿠키를 수용하고, 2단계에는 `MOM-0906`의 legacy base로 라우팅해 유지한다(`MOM-0871`, [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)) |
| H037 | Product JSON | `POST /workspaces/:id/projects` | `project.Create` | `PRJ` | W | `implemented`: target `POST /api/workspaces/{workspaceId}/projects`. 구현 완료(`MOM-0866`), 전환 전. 프로젝트 라벨은 `LabelAllocator`에서 발급해 INSERT 문에 포함한다. 레거시는 라벨이 없는 INSERT에 대해 트리거가 값을 채우지만 신규 서버에는 해당 트리거가 없다. 레거시는 워크스페이스가 없는 경우와 요청자의 권한이 부족한 경우를 모두 403으로 응답한다. 신규 서버는 공통 전환 규칙에 따라 워크스페이스 미존재는 404, 권한 부족은 403으로 구분한다. 소유자 목록에 같은 사용자 식별자가 중복되면 레거시가 멤버 수를 `COUNT(DISTINCT user_id)`로 계산해 요청한 소유자 수와 비교하므로 검증에 실패한다. 신규 서버도 동일한 동작을 유지한다. 응답에서 `progress`를 제외한 것은 웹 snapshot 계약 4.3절의 결정을 생성 응답에도 적용한 것이다. 레거시는 프로젝트 이름을 권한보다 먼저 검증하지만 신규 서버는 권한을 먼저 확인한다. 따라서 이름이 비어 있으면서 권한도 없는 요청에 레거시는 400, 신규 서버는 403으로 응답한다. H052와 동일한 차이를 가진다 |
| H038 | Product JSON | `GET /workspaces/:id/projects` | `project.List` | `PRJ` | R | `traced`. 구현 `MOM-0857` |
| H039 | Product JSON | `GET /workspaces/:id/blockers` | `blocker.List` | `BLK` | R | `traced`. blocker read 기반만 구현(`MOM-0859`), endpoint는 전환 대상이 아니다. 웹 소비자가 snapshot 폴백뿐이며 blocker 데이터는 H023으로 소비된다 |
| H040 | Product JSON | `GET /workspaces/:id/source-connections` | `source.List` | `SRC` | R | `implemented`: target `GET /api/workspaces/{workspaceId}/source-connections`. 구현 완료(`MOM-0870`), 전환 전. 레거시는 워크스페이스가 없는 경우와 요청자가 멤버가 아닌 경우를 모두 403으로 응답하지만, 신규 서버는 공통 전환 규칙에 따라 워크스페이스 미존재는 404, 권한 부족은 403으로 구분한다. 정렬 기준은 레거시 쿼리와 동일한 생성 시각 내림차순이다 |
| H041 | Product JSON | `GET /workspaces/:id/source-connections/install` | `source.Install` | `SRC` | W | `implemented`: target `GET /api/workspaces/{workspaceId}/source-connections/install`. provider 승인 화면으로 redirect하는 흐름을 시작한다. 구현 완료(`MOM-0870`), 전환 전. 승인 URL의 쿼리 파라미터 이름과 순서, scope 조합 방식은 레거시 실행 결과와 문자 단위로 대조해 확정했다. provider 설정이 없으면 레거시는 501로 응답하지만, 신규 서버의 status 목록에는 501이 없어 500 `SOURCE_PROVIDER_UNCONFIGURED`로 응답한다 |
| H042 | Product JSON | `GET /workspaces/:id/memory-candidates` | `candidate.List` | `MEM` | R | `traced`. read 기반만 구현(`MOM-0860`), endpoint는 전환 대상이 아니다. 웹 소비자가 snapshot 폴백(`workspaceSnapshot.ts:120`)뿐임이 FE 기준선에서 확인됐다. 후보 데이터는 H023으로 소비된다 |
| H043 | Product JSON | `POST /workspaces/:id/memories` | `memory.Create` | `MEM` | W | `traced`; projection 동반. **웹 미호출**(`MOM-0856`). 소비자 전수 검증에서 호출처가 없어 **retire 후보로 확정**(`MOM-0901`). 기존 클라이언트가 없어 레거시 계약을 보존할 이유가 없다. 필요해지면 신규 서버에서 새로 설계한다 |
| H044 | Product JSON | `GET /workspaces/:id/memories` | `memory.List` | `MEM` | R | `traced`. read 기반만 구현(`MOM-0860`), endpoint는 전환 대상이 아니다. 웹 소비자가 snapshot 폴백(`workspaceSnapshot.ts:121`)뿐임이 FE 기준선에서 확인됐다. 메모리 데이터는 H023으로 소비된다 |
| H045 | Product JSON | `POST /workspaces/:id/minsu/query` | `minsu.Query` | `MIN` | R | `traced`; retrieval·LLM 외부 호출 |
| H046 | Product JSON | `POST /invitations/accept` | `workspace.AcceptInvitation` | `WSP` | W | `implemented`: target `POST /api/invitations/accept`. 구현 완료(`MOM-0865`), 전환 전. 토큰 조회부터 멤버십 생성과 초대 수락 상태 반영까지 하나의 트랜잭션으로 처리하며, 같은 토큰을 사용한 동시 요청이 모두 통과하지 않도록 초대 행에 lock을 건다. 검증 순서는 레거시와 동일하게 초대 상태, 만료 여부, 이메일 일치 여부 순이다. 토큰은 레거시와 같은 방식으로 SHA-256 해시하므로 전환 전에 이미 발송된 초대 링크도 사용할 수 있다 |
| H047 | Product JSON | `GET /projects/:projectId` | `project.Get` | `PRJ` | R | `traced` |
| H048 | Product JSON | `PATCH /projects/:projectId` | `project.Update` | `PRJ` | W | `traced`: 웹 미호출. `MOM-0866` 착수 시 소비자를 확인한 결과 호출하는 코드가 없어 이번 범위에서 제외했다. `momens-fe`의 `src/api/client.ts`에 대응하는 메서드가 없다. 레거시 MCP에도 프로젝트 수정 도구가 없다. 이관 여부는 별도 판단 작업에서 결정한다 |
| H049 | Product JSON | `DELETE /projects/:projectId` | `project.Delete` | `PRJ` | W | `traced`: soft delete, 웹 미호출. 확인 근거와 후속 처리는 H048과 같다 |
| H050 | Product JSON | `POST /projects/:projectId/milestones` | `milestone.Create` | `MIL` | W | `implemented`: target `POST /api/projects/{projectId}/milestones`. 구현 완료(`MOM-0866`), 전환 전. 소유자를 지정하지 않으면 프로젝트 소유자를 사용하고, 프로젝트 소유자도 없으면 요청자를 소유자로 지정한다. 프로젝트 생성과 달리 member 이상의 권한으로 호출할 수 있다. 프로젝트가 없으면 404로 응답한다. 마일스톤 이름과 권한의 검증 순서 차이는 H037과 같다 |
| H051 | Product JSON | `GET /projects/:projectId/milestones` | `milestone.List` | `MIL` | R | `traced`. read 기반만 구현(`MOM-0858`), endpoint는 전환 대상이 아니다. 웹 소비자가 snapshot 폴백(`workspaceSnapshot.ts:130`)뿐임이 FE 기준선에서 확인됐다. 마일스톤 데이터는 H023으로 소비된다 |
| H052 | Product JSON | `POST /projects/:projectId/tasks` | `task.Create` | `TSK` | W | `implemented` (`MOM-0867`); `task.created` outbox 계약을 따른다. worker task projector·legacy MCP·Slack을 포함한 aggregate writer 단일화가 cutover gate |
| H053 | Product JSON | `GET /projects/:projectId/tasks` | `task.List` | `TSK` | R | `implemented` (`MOM-0861`); 모바일 보드 계약과 별도 |
| H054 | Product JSON | `POST /projects/:projectId/decisions` | `decision.Create` | `DEC` | W | `traced`; projection 동반 |
| H055 | Product JSON | `GET /projects/:projectId/decisions` | `decision.List` | `DEC` | R | `traced`; 웹 미호출. 구현 작업 미생성 |
| H056 | Product JSON | `GET /milestones/:milestoneId` | `milestone.Get` | `MIL` | R | `traced`. 전환 대상이 아니다. 웹 클라이언트에 호출 코드가 없다(FE 기준선 `src/api/client.ts`에 대응 메서드 없음). `MOM-0858`은 단건 조회 public API를 두지 않았다 |
| H057 | Product JSON | `PATCH /milestones/:milestoneId` | `milestone.Update` | `MIL` | W | `traced`: 웹 미호출. `MOM-0866` 착수 시 확인한 결과 `momens-fe`에 호출 코드가 없어 이번 범위에서 제외했다. 하지만 레거시 MCP 도구인 `update_milestone`은 같은 서비스 함수를 직접 호출한다(`mcpserver/milestones.go:162`). HTTP endpoint는 사용하지 않지만 기능 자체는 사용 중이므로, MCP 도구를 이관할 때 해당 기능도 함께 옮겨야 한다. 이관 여부는 별도 판단 작업에서 결정한다 |
| H058 | Product JSON | `DELETE /milestones/:milestoneId` | `milestone.Delete` | `MIL` | W | `traced`: soft delete, 웹 미호출. 레거시 MCP 도구인 `delete_milestone`은 같은 서비스 함수를 직접 호출한다(`mcpserver/milestones.go:192`). 확인 근거와 후속 처리는 H057과 같다 |
| H059 | Product JSON | `POST /milestones/:milestoneId/blockers` | `blocker.CreateForMilestone` | `BLK` | W | `traced`; projection 동반 |
| H060 | Product JSON | `GET /tasks/:taskId` | `task.Get` | `TSK` | R | `implemented` (`MOM-0861`); 모바일 상세 계약과 별도 |
| H061 | Product JSON | `PATCH /tasks/:taskId` | `task.Update` | `TSK` | W | `implemented` (`MOM-0867`); 모바일 수정·체크리스트 계약과 별도. worker task projector와 aggregate writer 단일화가 cutover gate |
| H062 | Product JSON | `DELETE /tasks/:taskId` | `task.Delete` | `TSK` | W | `implemented` (`MOM-0867`); soft delete. worker task projector와 aggregate writer 단일화가 cutover gate |
| H063 | Product JSON | `GET /tasks/:taskId/updates` | `task.ListUpdates` | `TSK` | R | `implemented` (`MOM-0861`) |
| H064 | Product JSON | `POST /tasks/:taskId/updates` | `task.CreateUpdate` | `TSK` | W | `implemented` (`MOM-0867`); worker task projector와 aggregate writer 단일화가 cutover gate |
| H065 | Product JSON | `DELETE /tasks/:taskId/updates/:updateId` | `task.DeleteUpdate` | `TSK` | W | `implemented` (`MOM-0867`); soft delete. worker task projector와 aggregate writer 단일화가 cutover gate |
| H066 | Product JSON | `POST /tasks/:taskId/blockers` | `blocker.CreateForTask` | `BLK` | W | `traced`; projection 동반 |
| H067 | Product JSON | `POST /tasks/:taskId/memories/:memoryId` | `relation.LinkTaskMemory` | `CTX` | W | `implemented` (`MOM-0868`): target `POST /api/tasks/{taskId}/memories/{memoryId}`. 이미 연결되어 있으면 변경하지 않고 같은 응답을 반환한다. 두 대상이 서로 다른 워크스페이스에 속하면 400 `CONTEXT_CROSS_WORKSPACE_LINK_NOT_ALLOWED`로 응답한다. |
| H068 | Product JSON | `DELETE /tasks/:taskId/memories/:memoryId` | `relation.UnlinkTaskMemory` | `CTX` | W | `implemented` (`MOM-0868`): target `DELETE /api/tasks/{taskId}/memories/{memoryId}`. 연결을 소프트 삭제한다. 해제할 연결이 없으면 404 `CONTEXT_LINK_NOT_FOUND`로 응답한다. 타깃 메모리가 존재하는지는 레거시와 동일하게 확인하지 않는다. |
| H069 | Product JSON | `POST /tasks/:taskId/source-refs` | `relation.CreateTaskSourceRef` | `CTX` | W | `implemented` (`MOM-0868`): target `POST /api/tasks/{taskId}/source-refs`. source-ref 생성과 연결을 하나의 트랜잭션으로 처리한다. 레거시는 두 SQL 문을 트랜잭션 없이 실행한다. `content_hash`는 저장하지 않으며, 같은 주소를 두 번 첨부하면 레거시와 동일하게 행이 두 건 생성된다. |
| H070 | Product JSON | `POST /tasks/:taskId/source-refs/:sourceRefId` | `relation.LinkTaskSourceRef` | `CTX` | W | `traced`: `MOM-0868` 범위에서 제외했다. 「웹 FE 사용 실태」 분류에서 웹 미호출이며, `momens-fe`의 `src/api/client.ts`에도 대응하는 메서드가 없다. 클라이언트가 소비할 일이 생기면 별도 이관 작업을 진행한다. |
| H071 | Product JSON | `DELETE /tasks/:taskId/source-refs/:sourceRefId` | `relation.UnlinkTaskSourceRef` | `CTX` | W | `implemented` (`MOM-0868`): target `DELETE /api/tasks/{taskId}/source-refs/{sourceRefId}`. 연결만 소프트 삭제하고 source-ref 행은 삭제하지 않는다. H068과 같은 기준으로 해제 여부를 판정한다. |
| H072 | Product JSON | `GET /tasks/:taskId/context` | `relation.TaskContext` | `CTX` | R | `implemented` (`MOM-0861`); memory·source-ref hydrate. 링크가 없을 때는 레거시 단건 응답의 `null` 대신 빈 배열로 정규화. hydrate는 대상 memory/source-ref의 workspace가 relation workspace와 같은지도 확인한다(레거시보다 방어적) |
| H073 | Product JSON | `GET /decisions/:decisionId` | `decision.Get` | `DEC` | R | `traced`; 웹 미호출. 구현 작업 미생성 |
| H074 | Product JSON | `PATCH /blockers/:blockerId/resolve` | `blocker.Resolve` | `BLK` | W | `traced`; projection 동반 |
| H075 | Product JSON | `DELETE /blockers/:blockerId` | `blocker.Delete` | `BLK` | W | `traced`; admin/owner, blocker 물리 삭제 + retrieval document soft-delete |
| H076 | Product JSON | `GET /source-connections/:id` | `source.Get` | `SRC` | R | `traced` |
| H077 | Product JSON | `PATCH /source-connections/:id` | `source.Update` | `SRC` | W | `traced` |
| H078 | Product JSON | `POST /source-connections/:id/disable` | `source.Disable` | `SRC` | W | `traced`; worker ingest 중지 계약 확인 |
| H079 | Product JSON | `POST /source-connections/:id/resync` | `source.Resync` | `SRC` | W | `traced`; worker가 관측하는 sync state 변경 |
| H080 | Product JSON | `POST /source-connections/:id/figma/configure` | `source.ConfigureFigma` | `SRC` | W | `traced`; Figma webhook 외부 호출 |
| H081 | Product JSON | `GET /source-connections/:id/sync-states` | `source.ListSyncStates` | `SRC` | R | `traced`; **웹 미호출**(`MOM-0856`) |
| H082 | provider callback | `GET /source-connections/oauth/callback` | `source.OAuthCallback` | `SRC` | W | `implemented`: target `GET /api/source-connections/oauth/callback`. 구현 완료(`MOM-0870`), 전환 전. provider가 호출하는 공개 경로이며, 요청자의 신원과 연결 대상은 서명된 state만으로 판정한다. 서명 비밀, 발급자, 대상, claim 이름을 레거시와 동일하게 유지해 전환 기간에도 한쪽 서버가 발급한 state를 다른 서버가 검증할 수 있도록 했다. 저장하는 토큰의 암호화 형식도 레거시 및 `momens-worker`와 동일하다. **신규 경로에는 레거시와 달리 `/api` 접두사가 있으므로, 이관 시점에 provider 관리 화면에 등록된 redirect URI도 함께 변경해야 한다** |
| H083 | Product JSON | `GET /memory-candidates/:id` | `candidate.Get` | `MEM` | R | `traced`. 전환 대상이 아니다. 웹 클라이언트에 호출 코드가 없다(FE 기준선 `src/api/client.ts`에 대응 메서드 없음). `MOM-0860`은 단건 조회 public API를 두지 않았다 |
| H084 | Product JSON | `POST /memory-candidates/:id/confirm` | `candidate.Confirm` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memory-candidates/{candidateId}/confirm`. confirmed memory·review action을 한 트랜잭션에 남기고 projection은 `memory.confirmed` outbox로 대신한다. 공통 consumer(`MOM-0898`)와 memory projector·prod E2E 검증(`MOM-0957`)이 cutover gate |
| H085 | Product JSON | `POST /memory-candidates/:id/reject` | `candidate.Reject` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memory-candidates/{candidateId}/reject`. review action만 남기고 projection 없음 |
| H086 | Product JSON | `POST /memory-candidates/:id/merge` | `candidate.Merge` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memory-candidates/{candidateId}/merge`. target memory는 잠금·존재 확인만 하고 candidate `MERGED` + review action, projection 없음. 요청 필드는 레거시 `merge_target_memory_id`를 그대로 쓴다 |
| H087 | Product JSON | `POST /memory-candidates/:id/expire` | `candidate.Expire` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memory-candidates/{candidateId}/expire`. **웹 미호출**이라 전환 효용은 없고 후보 상태 모델을 닫기 위해 함께 구현했다 |
| H088 | Product JSON | `POST /memory-candidates/:id/edit-and-confirm` | `candidate.EditAndConfirm` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memory-candidates/{candidateId}/edit-and-confirm`. 비어 있지 않은 편집 필드만 덮어쓰고 review action은 `EDIT_AND_CONFIRM`. H084와 같은 projection gate |
| H089 | Product JSON | `GET /memories/:id` | `memory.Get` | `MEM` | R | `traced`. 전환 대상이 아니다. 웹 클라이언트에 호출 코드가 없다(FE 기준선 `src/api/client.ts`에 대응 메서드 없음). `MOM-0860`은 단건 조회 public API를 두지 않았다 |
| H090 | Product JSON | `PATCH /memories/:id` | `memory.Update` | `MEM` | W | `traced`; projection 동반. **웹 미호출**(`MOM-0856`). 소비자 전수 검증에서 호출처가 없어 **retire 후보로 확정**(`MOM-0901`) |
| H091 | Product JSON | `POST /memories/:id/invalidate` | `memory.Invalidate` | `MEM` | W | `traced`; projection 동반. **웹 미호출**(`MOM-0856`). **retire 후보로 확정**(`MOM-0901`). `INVALIDATED` 상태를 만드는 유일한 경로라, 이관하지 않으면 신규 서버는 그 상태를 새로 만들지 않는다. 레거시가 남긴 기존 행이 있어 read 경로는 계속 다룬다(`MOM-0860`) |
| H092 | Product JSON | `POST /memories/:id/archive` | `memory.Archive` | `MEM` | W | `traced`; projection 동반. **웹 미호출**(`MOM-0856`). **retire 후보로 확정**(`MOM-0901`). `ARCHIVED`는 H093 resolve 가 만들므로 이 경로 없이도 생긴다 |
| H093 | Product JSON | `POST /memories/:id/resolve` | `memory.Resolve` | `MEM` | W | `implemented` (`MOM-0869`): target `POST /api/memories/{memoryId}/resolve`. `RESOLVES` 관계(`:context` writer)와 대상 `ARCHIVED`를 한 트랜잭션에 두고 `memory.updated` outbox를 남긴다. H084와 같은 `MOM-0898`·`MOM-0957` projection gate |
| H094 | Product JSON | `DELETE /memories/:id` | `memory.Delete` | `MEM` | W | `traced`; soft delete·projection 동반. **웹 미호출**(`MOM-0856`). **retire 후보로 확정**(`MOM-0901`). `DELETED` 상태와 `deleted_at`을 만드는 유일한 경로다 |
| H095 | Product JSON | `GET /memories/:id/linked-tasks` | `relation.LinkedTasks` | `CTX` | R | `traced`; **웹 미호출**(`MOM-0856`). MOM-0861 범위에서 제외 확정; 소비자가 생기면 별도 이관 작업을 만든다 |
| H096 | Product JSON | `POST /source-refs/:id/verify` | `source.VerifySourceRef` | `SRC` | W | `implemented`: target `POST /api/source-refs/{sourceRefId}/verify`. 구현 완료(`MOM-0870`), 전환 전. 레거시와 동일하게 source-ref의 전체 필드를 반환한다. source-ref가 없거나 소프트 삭제된 경우에는 404 `SOURCE_REF_NOT_FOUND`로 응답한다 |

## 비-HTTP·도구 진입점 원장

N001~N008은 HTTP capability profile만으로 전환 필드를 복원할 수 없어 아래 전용 profile을 사용한다.
HTTP 인증이 없는 항목도 실행 주체와 자격증명을 적고, prod/client gate가 없으면 이유와 함께 N/A로
기록한다. `task`는 공통 trace task `MOM-0848`이며 별도 구현·결정 작업은 아직 만들지 않았다.

| Profile | contract·auth/RBAC | legacy trace·schema·test | target·writer/projection/external | prod/client gate | rollback |
| --- | --- | --- | --- | --- | --- |
| `N-MIG` | 프로세스 startup에서 SQL 파일을 lexical order로 실행하고 파일별 transaction·version row·advisory lock을 보장. 최종 사용자 auth는 N/A, 배포 runtime의 migration DB 권한이 실행 권한 | `bootstrap/app.go` → `platform/db/migrations.go` → `migrations/*.sql`; `schema_migrations`; `platform/db/migrations_integration_test.go` | local/test는 target module Flyway, prod DDL writer는 부트스트랩까지 `momens-api`; 최종 owner는 momens-server로 확정(ADR-0019) | prod DDL 소유권·부트스트랩은 [ADR-0019](../../adr/0019-prod-schema-ownership-transfer.md)와 [주도권 이전 설계](../prod-schema-ownership-transfer.md), 부트스트랩 전 수기 릴리스 의무는 [prod 운영 준비 대장](../../prod-readiness-ledger.md)에서 확인. 외부 client gate는 N/A, deploy/startup gate만 존재 | 실패한 파일 transaction은 rollback되지만 이미 적용된 이전 파일의 down migration은 없음. 배포 rollback과 schema rollback을 분리하고 객체별 보상 절차 없이는 retire 금지 |
| `N-BACKFILL` | startup goroutine이 live row의 NULL `search_tokens`를 batch로 채우며 조건부 UPDATE로 재실행·경합에 안전. 최종 사용자 auth는 N/A, runtime DB 권한으로 실행 | `bootstrap/app.go` → `retrieval/backfill.go`·`tokenizer.go`; `retrieval_documents.search_tokens`; `retrieval/backfill_test.go`, `retrieval/tokenizer_test.go` | `retrieval_documents.search_tokens` writer; target `momens-worker`/retrieval projection owner 미결, 외부 gateway 없음 | prod gate는 owner·재처리 계약과 NULL backlog 관측. client gate는 N/A | goroutine을 중단해 rollback하며 이미 계산된 파생 token은 유지 가능. tokenizer 계약이 바뀌면 전체 재계산 절차가 필요 |
| `N-EMBED` | startup 뒤 즉시 stale row를 drain하고 ticker로 반복. text race guard와 model/dimension 검증을 사용. 최종 사용자 auth는 N/A, Vertex ADC와 runtime DB 권한으로 실행 | `bootstrap/app.go` → `retrieval/embedder.go` → `platform/llm/embeddings.go`; `retrieval_documents.embedding`, `embedding_model`, `text_hash`; `retrieval/embedder_test.go`, `platform/llm/embeddings_test.go` | embedding writer, Vertex AI; target `momens-worker`/retrieval owner 미결 | prod gate는 owner 단일화, model·dimension·ADC·비용·지연·중복 실행 관측. client gate는 N/A | loop를 끄고 lexical 검색으로 후퇴하며 기존 vector는 유지. owner/model 전환 시 stale 판정과 재embedding 가능성을 확인 |
| `N-SLK` | H013의 Slack signature·retry·3초 ack 계약을 공유하고 app mention을 goroutine에서 answer/post. Slack 서명과 구성된 bot identity가 권한 경계 | `slackbot/handler.go` → `answerAndPost`, `grounded.go`, `action.go` → `minsu/*`; `slackbot/handler_test.go`, `grounded_test.go`, `action_test.go` | Slack API·retrieval·Vertex, action이면 task writer; target `minsu` + `project` public API | signing secret·bot identity·event URL·timeout·실패 관측과 task projection 준비. Slack event URL이 client gate | event URL을 legacy로 되돌리고 신규 유입을 중단. 현재 child goroutine drain 관리가 없으므로 최대 answer/post 시간의 in-flight 유실 허용 여부와 Slack retry를 runbook에 명시 |
| `N-SRV` | HTTP accept를 goroutine에서 시작하고 SIGINT/SIGTERM 뒤 10초 drain, enrichment → retrieval client → DB 순서로 close. 사용자 auth/RBAC는 각 HTTP entry가 소유 | `cmd/api/main.go` → `bootstrap/app.go`의 `App.Close`; lifecycle 직접 test 없음(`bootstrap/app_test.go`는 조립 설정 일부만 검증) | target `app` Spring lifecycle와 k8s; domain writer 없음, HTTP·gRPC·DB lifecycle | readiness/liveness, ingress, termination grace와 connection drain이 prod/client gate | deploy·routing rollback. 종료 grace 안에 요청과 background 작업이 끝나는지 확인 전 legacy lifecycle retire 금지 |
| `N-SEED` | deterministic demo fixture를 DB-direct transaction으로 적용. 최종 사용자 auth는 N/A, 실행 운영자가 DB 권한을 소유하며 prod는 명시적 `--allow-production` 없이는 거부 | `cmd/seed-demo/main.go` → `demo/seed.go`; 선택적 `migrations/*.sql`; `demo/seed_test.go` | local/demo DB seed writer; target runtime으로 이관하지 않음 | prod 적용 제외가 기본 gate. 외부 client gate는 N/A, 로컬 운영자 CLI 계약만 존재 | 실패 시 transaction rollback. `--reset`은 deterministic workspace만 재생성 가능하지만 `--truncate` 뒤 자동 복원은 없으므로 DB backup 없이는 실행·retire 판단 금지 |
| `N-ASK` | 질문을 args/stdin으로 받아 단일 ungrounded 답변을 stdout에 출력. 최종 사용자 auth/RBAC는 N/A, 로컬 운영자의 Vertex ADC가 실행 권한 | `cmd/minsu-ask/main.go` → `slackbot/answerer.go` → `platform/llm/client.go`; 직접 test 없음, answerer test는 `slackbot/answerer_test.go` | DB writer 없음, Vertex AI 호출; target runtime으로 이관하지 않음 | prod/client gate는 N/A인 개발 검증 도구. ADC·model allowlist·비용만 확인 | 프로세스 중단으로 rollback, 영속 상태 없음 |
| `N-EVAL` | JSON eval set을 retrieval gRPC로 실행해 Recall@nDCG·MRR을 stdout에 출력하며 synthetic owner permission을 사용. 제품 사용자 auth/RBAC는 N/A, 로컬 운영자가 retrieval 접근권한과 선택적 Vertex ADC를 소유 | `cmd/minsu-eval/main.go` → `eval/eval.go`, `metrics.go` → `retrieval/client.go`; `eval/eval_test.go`, `retrieval/client_test.go` | writer 없음, retrieval gRPC와 선택적 Vertex embedding; target 소유 저장소 미결 | prod gate는 N/A인 offline 평가 도구. client gate는 eval set·retrieval 주소·선택적 ADC | 프로세스 중단으로 rollback, 영속 상태 없음. 출력 보고서만 폐기 가능 |

| ID | surface | legacy entry point·trace | profile/target | writer·dependency | status·gate |
| --- | --- | --- | --- | --- | --- |
| N001 | startup migration | `bootstrap.New` → `db.RunMigrations` → `migrations/*.sql` | `N-MIG` → infra | schema writer, advisory lock | `traced`; prod는 현재 legacy owner 유지, 최종 DDL 소유권 미결 |
| N002 | startup backfill | `bootstrap.New` goroutine → `retrieval.BackfillSearchTokens` | `N-BACKFILL` → retrieval/worker 경계 | `retrieval_documents.search_tokens` writer | `traced`; worker projection·backfill owner와 재처리 계약 필요 |
| N003 | background loop | `bootstrap.New` goroutine → `retrieval.Embedder.Run` ticker | `N-EMBED` → retrieval/worker 경계 | retrieval document embedding writer, Vertex AI | `traced`; 중복 실행·비용·지연 관측과 owner 결정 필요 |
| N004 | webhook child runtime | `slackbot.Handler.dispatch` → goroutine `answerAndPost` | `N-SLK` → `minsu` | Slack API·retrieval·Vertex, action이면 task write | `traced`; H013과 같은 전환 단위 |
| N005 | server lifecycle | `cmd/api/main.go`의 `ListenAndServe` goroutine, signal drain, `App.Close` | `N-SRV` → `app` | HTTP accept, retrieval client, DB pool lifecycle | `traced`; Spring lifecycle와 k8s probe·termination grace로 대체 |
| N006 | offline CLI | `cmd/seed-demo` → `demo.Run` | `N-SEED`; 신규 runtime으로 이관하지 않음 | local/demo DB seed, 선택적 migration | `traced`; 유지·대체·폐기 명시 필요, prod guard 보존 |
| N007 | offline CLI | `cmd/minsu-ask` → ungrounded LLM answer | `N-ASK`; 신규 runtime으로 이관하지 않음 | Vertex AI/ADC | `traced`; 개발 검증 도구로 유지할지 결정 |
| N008 | offline CLI | `cmd/minsu-eval` → `eval.Run` → retrieval gRPC | `N-EVAL`; 신규 runtime으로 이관하지 않음 | retrieval·선택적 Vertex embedding | `traced`; 별도 eval 도구 소유 저장소 결정 |
| N009 | MCP tool | `list_projects` → `mcpserver.listProjects` | `MCP` → `project` | read-only | `traced`; H012와 같은 전환 단위 |
| N010 | MCP tool | `list_members` → `mcpserver.listMembers` | `MCP` → `workspace` | read-only | `traced`; H012와 같은 전환 단위 |
| N011 | MCP tool | `list_milestones` → `mcpserver.listMilestones` | `MCP` → `project` | read-only | `traced`; H012와 같은 전환 단위 |
| N012 | MCP tool | `create_milestone` → `mcpserver.createMilestone` | `MCP` → `project` | milestone writer | `traced`; REST milestone writer와 함께 전환 |
| N013 | MCP tool | `update_milestone` → `mcpserver.updateMilestone` | `MCP` → `project` | milestone writer | `traced`; REST milestone writer와 함께 전환 |
| N014 | MCP tool | `delete_milestone` → `mcpserver.deleteMilestone` | `MCP` → `project` | milestone writer | `traced`; REST milestone writer와 함께 전환 |
| N015 | MCP tool | `create_task` → `mcpserver.createTask` | `MCP` → `project` | task writer | `traced`; REST·Slack task writer와 projection을 함께 전환 |
| N016 | MCP tool | `update_task` → `mcpserver.updateTask` | `MCP` → `project` | task writer | `traced`; REST·Slack task writer와 projection을 함께 전환 |
| N017 | MCP tool | `get_task` → `mcpserver.getTask` | `MCP` → `project` | task·updates read | `traced`; H012와 같은 전환 단위 |
| N018 | MCP tool | `list_tasks` → `mcpserver.listTasks` | `MCP` → `project` | read-only | `traced`; H012와 같은 전환 단위 |
| N019 | MCP tool | `create_comment` → `mcpserver.createComment` | `MCP` → `project` | task update writer | `traced`; REST task update writer와 함께 전환 |

## 첫 수직 슬라이스 후보 비교

첫 슬라이스는 **워크스페이스 목록·상세(H020, H022)** 로 확정했다(`MOM-0850`). 계약은
[첫 웹 read 슬라이스 계약](slice-workspace-read.md)이 잠갔다. 아래는 확정 당시
비교한 read-only 후보이며, write 전환과 projection 공통 기반을 처음부터 묶지 않아도 되는 범위만
비교했다.

| 후보 | 포함 entry | 장점 | 먼저 잠글 계약·제약 | 판단 |
| --- | --- | --- | --- | --- |
| 워크스페이스 목록·상세 | H020, H022 | 사용자 진입 가치가 높고 target `workspace` entity/repository가 이미 있으며 projection 없음 | legacy wrapper·403/404·soft-delete characterization | **확정**. 응답 필드가 target 엔티티와 1:1이고 신규 DDL이 없음. 웹 계약이라 `MOM-0845`와 독립 |
| 프로젝트 목록·상세 | H038, H047 | target `ProjectReader`와 project backing이 이미 있고 projection 없음 | legacy `health_status`, count, metadata, label, owners와 계산 progress의 응답 정책; `MOM-0845` | ~~두 번째 후보~~ **무효.** FE 사용 실태 확인 결과 H038은 snapshot 폴백 전용이고 H047은 호출처가 없다(`MOM-0856`). 프로젝트 데이터는 H023을 통해 소비되므로 read 기반만 필요하다(`MOM-0857`) |
| 마일스톤 목록·상세 | H051, H056 | read-only이고 외부 provider·projection 없음 | target milestone entity/API가 아직 없고 owner·health·progress 전체 mapping 필요 | ~~독립성은 높지만 첫 slice의 신규 코드량이 더 큼~~ **무효.** H051은 snapshot 폴백 전용, H056은 호출처 없음. read 기반만 필요하다(`MOM-0858`) |
| 태스크 목록·상세 | H053, H060 | 사용자 가치가 높고 target task backing·모바일 read가 존재 | `MOM-0773`, legacy milestone/due date·role/default·progress, 웹/모바일 DTO 분리 | 절반 무효. H053은 snapshot 폴백 전용이고 H060만 실사용이다(`MOM-0861`) |
| 결정·블로커 read | H039, H055, H073 | write를 제외하면 projection 전환 없이 routing rollback 가능 | target entity/API 미구현, legacy 전용 test 없음, 현재 클라이언트 사용 근거 확인 필요 | ~~characterization 근거가 약해 후순위~~ **무효.** H039는 snapshot 폴백 전용이라 H023 합성용 blocker read 기반만 구현했다(`MOM-0859`). H055·H073은 호출처가 없어 decision read 기반도 만들지 않는다 |

확정한 슬라이스의 route → handler → service → repository → schema → test 재추적 결과와 잠근
계약은 [첫 웹 read 슬라이스 계약](slice-workspace-read.md)에 있다.

## 미결정 사항

구현 중 조용히 정하지 않는다.

1. ~~웹 트래픽을 capability별로 혼합 전환할지, 신규 인증과 준비된 Product API를 한 번에 전환할지~~
   — 해소. Product capability별 혼합 전환은 기각하고 인증과 Product API를 2단계로 나눴다. 다만
   미이관 OAuth interaction·MCP grant UI는 한시적 legacy base로 격리한다. 두 단계의 게이트와
   롤백 절차는 [웹 컷오버 실행과 rollback runbook](cutover.md)에 있다(`MOM-0911`)
2. MCP transport·OAuth authorization server의 target Gradle module과 grant/token 이전 방식
   — 미결정으로 남는다. 다만 **웹 컷오버 시점의 동작만은 분리해 결정했다**. 레거시가 신규
   `access_token`을 수용해 H009~H011·H035·H036을 유지한다
   ([ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md), `MOM-0871`).
   표면 전체의 이관은 여전히 이 항목이 풀어야 한다
3. `momens-worker`의 공통 outbox 소비 기반(offset·멱등·재시도·DLQ)과 task/decision/blocker/memory
   projector 분리
4. startup retrieval backfill·embedding의 최종 owner와 기존 document 재projection 방식
5. ~~snapshot 합성 endpoint의 합성 로직 소유와 응답 계약~~ — 해소. 표면·합성 모두 `:web`이고
   (`MOM-0850`) 응답 계약은 [웹 snapshot 계약](slice-snapshot.md)이
   잠갔다(`MOM-0856`)
6. offline CLI 3개의 유지·대체·폐기와 소유 저장소
7. `MOM-0773` task 계약, `MOM-0774` source-ref 관계, `MOM-0845` workspace scope
8. ~~Product JSON별 실제 웹 사용 여부~~ — 해소. 위 [웹 FE 사용 실태](#웹-fe-사용-실태)에 기록했다
   (`MOM-0856`). [컷오버 문서](cutover.md) 6절은 전환 직후 판정 창을 정했고, 전환기 코드 제거에
   필요한 시계열 관찰 기간은 `MOM-0875`가 `MOM-0834` 이후 정한다

## 후속 작업 제안

중복 여부를 Momens에서 다시 확인한 뒤 필요한 것만 만든다.

1. ~~`[Docs] 첫 웹 read 수직 슬라이스 선정과 계약 잠금`~~ — `MOM-0850`에서 완료.
   H020·H022로 확정하고 [계약 문서](slice-workspace-read.md)로 잠갔다
2. `[Docs] MCP/OAuth target module·token/grant 전환 ADR`
   - H002~H012, H035~H036, N009~N019 소유
   - 웹 컷오버 시점의 동작은 여기서 빠졌다. `MOM-0871`이
     [ADR-0018](../../adr/0018-transitional-legacy-acceptance-of-new-access-token.md)로 분리 결정했다
3. `[Feat] worker outbox 공통 소비 기반`
   - offset, idempotency, retry, DLQ와 관측성만 소유
4. aggregate별 projection 작업
   - task, decision, blocker, memory 이벤트 계약·hydrate·projector를 각각 분리
5. ~~`[Docs] 웹 인증·Product API 컷오버 단위와 rollback runbook`~~ — `MOM-0911`에서 전환 단위,
   관측 기준과 FE 배포·롤백 절차를 정했다. 실행 절차는 [컷오버 문서](cutover.md)에 있다. Product
   capability별 혼합은 하지 않지만 미이관 OAuth/MCP UI는 legacy base로 격리하며 ADR-0018의 세션
   호환을 유지한다
6. `[Docs] 레거시 offline CLI disposition 결정`
   - N006~N008 유지·이동·폐기와 실행 주체 확정

기존 작업은 새로 만들지 않는다. `MOM-0773`, `MOM-0774`, `MOM-0845`, `MOM-0909`를 해당 gate에서
참조한다.
