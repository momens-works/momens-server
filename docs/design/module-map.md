# 모듈 맵

현재 모듈의 책임과 코드만으로 드러나기 어려운 경계를 설명한다. 모듈 목록은
[settings.gradle](../../settings.gradle), 실제 의존성은 각 모듈의 `build.gradle`이 기준이다.
분리·접근 규칙은 [아키텍처](../rules/architecture.md), HTTP 필드는
[OpenAPI](../spec/openapi.md)를 따른다. 이관 상태는 [이관 원장](legacy-product-api-migration/ledger.md)에서 확인한다.

## 모듈 개요

| 모듈 | 책임 |
| --- | --- |
| `app` | 실행·조립, 스케줄링 인프라 활성화, 전체 컨텍스트·Modulith 검증 |
| `common` | 영속성 베이스·공유 확장·테스트 fixture·dev 도구 게이트. 도메인 helper는 두지 않음 |
| `user` | 사용자·로그인 수단·프로필. 사용자와 로그인 수단을 함께 생성 |
| `auth` | 로그인·세션·JWT·보안 체인과 쿠키·리다이렉트 정책 |
| `mcp` | MCP OAuth protocol·grant·token·transport·도구 |
| `workspace` | 워크스페이스·멤버십·초대·RBAC·라벨 발급 |
| `project` | 프로젝트·마일스톤·작업·작업 업데이트·decision·blocker |
| `signal` | Signal 원본 조회·사용자 action ledger·action outbox |
| `outbox` | 호출자 트랜잭션에 참여하는 append-only 이벤트 기록과 조회 |
| `notification` | Signal push 소비·발송·설치 lifecycle |
| `mobile` | 모바일 HTTP 표면과 화면 조합, 앱 최소 지원 버전 정책 |
| `web` | 웹 Product API 표면과 도메인 public API 조합 |
| `memory` | 후보 검토·확정 memory lifecycle |
| `source` | 외부 연결·provider OAuth·자격 증명·source-ref |
| `context` | 엔티티 연결의 읽기·쓰기. 본문 hydrate와 리소스 인가는 호출자가 수행 |
| `onboarding` | workspace·project·memory를 조합하는 초기 데이터 생성 트랜잭션 |
| `minsu` | draft 준비·비동기 생성 원장·LLM 경계 |

`retrieval`은 이 서버의 Gradle 모듈이 아니다. 서버는 도메인 변경과 outbox를 원자적으로 기록하고,
worker가 projection·소비 상태·재시도·DLQ를, `momens-retrieval`이 indexing을 담당한다
([ADR-0008](../adr/0008-outbox-worker-projection-boundary.md)). Signal push 소비는 이와 별도로
서버의 `notification` 책임이다([ADR-0009](../adr/0009-notification-consumer-ownership.md)).

## 표면과 조합 경계

- 클라이언트 HTTP 표면은 도메인 스코프가 분명해도 `web`·`mobile`이 소유한다. 인가와 응답 조합은
  public API를 사용한다. 도메인 엔티티·repository를 표면에 두지 않는다.
- 인증 표면은 `auth`가 완성한 인증 결과·쿠키 헤더·리다이렉트를 전달한다. 프로필 정책은 `user`다.
  dev 테스트 도구인 `auth.dev` 토큰 발급과 `signal.dev` Signal 생성은 capability 내부에 남는 예외다.
- `mobile.appupdate`는 영속성 없는 앱 호환 정책이므로 `mobile`이 직접 소유한다.
- `web`·`mobile`은 화면/자원별 nested 모듈을 둔다. 같은 패키지 전용 타입은 package-private,
  둘 이상이 공유하거나 외부에 필요한 계약만 root에 둔다. Java 접근 제어는 서브패키지까지 확장되지 않는다.
- `mcp`는 workspace public API로 grant와 요청의 현재 멤버십을 검증한다. transport는 공개
  `McpBearerTokenVerifier`를 사용하며 OAuth 내부 구현에 의존하지 않는다. grant 관리 HTTP는
  `web`이 소유한다([ADR-0024](../adr/0024-mcp-grant-web-surface.md)).
- `onboarding`은 자체 테이블 없이 workspace·project·memory public API를 한 트랜잭션으로 묶는다.
  이를 workspace에 두면 역방향 의존이 생긴다.
- Signal convert는 트랜잭션 밖에서 `minsu.prepare`, 안에서 `minsu.enroll`을 호출한다.
  minsu는 `TaskDraftApplier`와 outbox로 결과를 원자 반영한다. `project → minsu`는 순환이므로
  두 원장을 읽는 조합은 표면이 맡는다([Minsu 생성·반영 경계](#minsu-생성반영-경계)).

## mcp — 미사용 OAuth client 정리

`MOM-0996`은 **등록 후 24시간 이상 지났고 인가를 한 번도 시작하지 않은 client**를 정리한다.
`oauth2_authorization`, `oauth2_authorization_consent`, `mcp_grants`에 연결된 행이 하나도
없어야 한다. authorization·consent는 내부 등록 ID(`oauth2_registered_client.id`), grant는
공개 `client_id`로 대조한다. 인가를 시작했다면 중단·거절·만료했어도 보존하며, 폐기된 grant도
사용 이력으로 취급한다. 사용 기록의 보존 정책을 바꾸면 이 삭제 조건도 함께 재검토해야 한다.

24시간은 사용 규모가 작은 현재 단계에서 합의한 초기 운영 기준이며, 표준이나 통계에서
도출한 값은 아니다. 삭제된 등록으로 연결을 이어가면 재등록·재연결이 필요할 수 있다.
이미 사용한 연결의 비활성 기간을 삭제 기준으로 삼지 않는다. 실행 주기·배치 크기는
`modules/mcp`의 정리 스케줄러와 설정에서 확인한다.

- 후보 client 행을 `FOR UPDATE SKIP LOCKED`로 잠그고, 별도 DELETE 문에서 사용 이력을 다시
  확인한다. 여러 인스턴스는 잠긴 후보를 건너뛴다. 정리 트랜잭션의 timeout은 10초다.
- authorization·consent 저장은 같은 client 행의 `FOR KEY SHARE` 잠금을 트랜잭션 종료까지
  유지한다. 인가가 먼저 시작했으면 정리가 건너뛰고, 삭제가 먼저 완료되면 저장은
  `invalid_client`로 실패해 고아 authorization을 만들지 않는다. 운영 grant 생성은 저장된
  authorization의 승인 경로에서만 이루어진다. 별도 grant 생성 경로를 추가하면 같은 보호가 필요하다.
- 기본 활성이고 삭제 수와 실패를 로그로 남긴다. 정지를 원하면
  `MOMENS_MCP_OAUTH_UNUSED_CLIENT_CLEANUP_ENABLED=false`로 설정하고 재기동한다.
  정리 실행은 `app`의 공통 스케줄러를 사용한다.

**등록 API 레이트 리밋은 이번 범위에서 제외한다.** 서버의 공통 구현과 Ingress 제한 정책이
없어 적용 위치·식별 기준·다중 인스턴스 집계를 별도 작업에서 결정한다. 따라서 이번 정리는
단기 대량 등록을 막지 않으며, 인가를 시작한 등록이나 24시간 미만 등록은 남는다.
전역 저장 행 수 상한은 두지 않는다.

## workspace 하위 경계

내부는 하위 도메인별 named interface로 논리적으로 분리했습니다(MOM-70, MOM-0894).

- 공개 계약은 `core`(워크스페이스와 slug), `membership`(멤버십과 권한), `invitation`(초대),
  `label`(라벨 발급) 4개 패키지에 두고, 구현은 각 하위 도메인의 `internal` 패키지에 둡니다. 에러
  코드는 각 하위 도메인에서 관리하며, 모듈 루트에는 타입을 선언하지 않습니다(MOM-0959). 초대 이메일
  발송을 담당하는 `email`은 외부에 공개할 계약이 없으므로 nested 모듈로 유지합니다.
- Modulith는 named interface로 분리한 하위 도메인 간 의존 방향을 검증하지 못하므로
  `WorkspaceSubDomainBoundaryTests`에서 이를 고정합니다. 허용되는 의존 방향은 `core`에서
  `membership`으로, `invitation`에서 `core`, `membership`, `email`로 향하는 경우가 유일합니다.
- 사용자가 속한 워크스페이스를 조회할 때는 `membership`의 공개 계약으로 워크스페이스 ID를 확정한 뒤,
  `core`가 자신의 테이블을 조회합니다. `workspace_members`를 직접 조인하지 않습니다(MOM-0894).
- 각 하위 도메인은 하나의 aggregate(`Workspace`, `WorkspaceMember`, `WorkspaceInvitation`,
  `WorkspaceLabelSequence`)를 가지며, 트랜잭션 경계는 해당 aggregate 내부로 한정합니다. 예외는 라벨 발급 한 곳으로, 발급이
  단일 문장(UPSERT)으로 호출자 트랜잭션에 참여한다(MANDATORY). 라벨이 INSERT되는 행에 동기
  반환값으로 들어가고 실패 시 번호가 함께 되돌아가야 해서이고, 레거시 `BEFORE INSERT` 트리거와
  같은 시맨틱을 유지하는 의도된 예외다.

## project 하위 경계

내부는 도메인 하위 경계로 논리 분리했다(MOM-71·MOM-0887). `:project` Gradle 모듈은 프로젝트 운영
capability의 물리 경계로 유지하고, 배포 단위도 나누지 않는다.

- 프로젝트 코어는 `core`, 작업은 `task`, 작업 업데이트는 `taskupdate`, 마일스톤은 `milestone`,
  blocker는 `blocker`, decision은 `decision`에 두고 여섯
  패키지를 Spring Modulith named interface로 선언한다. 다른 상위 모듈은 nested application module을
  직접 참조할 수 없으므로 외부 공개 계약이 필요한 이 경계에는 nested module을 사용하지 않는다.
- 각 하위 도메인 root에는 해당 경계의 공개 계약만 두고 구현은 `core.internal`, `task.internal`,
  `taskupdate.internal`, `milestone.internal`, `blocker.internal`, `decision.internal`에 둔다. `works.momens.server.project` 최상위 패키지는
  namespace와 application module 선언만 소유하며 production Java 타입을 두지 않는다. 다른 Gradle
  모듈과 project 내부의 다른 하위 도메인도 필요한 named interface의 root API만 참조한다.
- project core는 다른 하위 도메인을 참조하지 않는다. 진행률은 task가 `TaskProgressReader`로 제공하고,
  task는 프로젝트 생존·workspace 조회에 `ProjectReader`, 마일스톤 소속 검증에 `MilestoneDirectory`를
  사용한다. milestone은 요청자를 기본 소유자로 사용하므로 런타임에서 core의 공개 계약에 의존하지
  않습니다. 하지만 `MilestoneDetail`의 Javadoc에서 `ProjectDetail`을 참조합니다. task repository가
  `Project`·`Milestone` 엔티티를 JPQL 문자열로 직접 조회하던 숨은 결합은 두 공개 계약으로 제거했다.
- taskupdate는 호출자가 확정한 workspace와 project 소속을 전달받아 사용하며, task 내부 저장소를 직접
  참조하지 않는다. 허용하는 의존 방향은 `taskupdate → task`이고, task는 taskupdate를 참조하지 않는다.
- decision은 `DecisionWriter`로 생성을 제공한다(`MOM-1017`). `:web`이 프로젝트와 권한을 확인한 뒤
  workspace를 전달하며, writer는 `ProjectReader`의 공유 잠금 조회로 프로젝트 활성 상태와 workspace를
  다시 확인한다. 허용하는 의존 방향은 `decision → core`이며, Decision 저장과 `decision.created`
  outbox 발행은 같은 트랜잭션에서 처리한다.
- blocker는 `BlockerReader`와 `BlockerWriter`로 조회·생성·해결·삭제를 제공한다. `:web`이 작업·프로젝트와
  권한을 확인한 뒤 workspace를 전달하며, blocker는 다른 project 하위 경계에 의존하지 않는다.
  도메인 변경과 outbox 발행 계약은 [Blocker 쓰기 명세](../spec/blocker-write.md)에 있다.
- `ProjectHealthStatus`, `MilestoneHealthStatus`와 소유자 멤버십 검증은 project와 milestone의 구현 계약이다. 현재 저장값과 검증 동작은
  같아도 변경 이유가 다르므로 각 하위 경계가 독립적으로 소유한다.
- 마일스톤 workspace 목록은 `milestones`에 workspace id가 없어 project를 조인한다. snapshot 쿼리 예산
  14회를 유지하기 위해 `MilestoneRepository`의 JPQL 조인을 persistence 예외로 허용한다. 엔티티 타입을
  Java 코드로 공유하거나 쓰기 경계를 넘기는 예외는 아니며, 통합 테스트가 workspace 격리와 project
  soft-delete 동작을 고정한다.
- 하위 도메인마다 aggregate가 하나씩이고(`Project`, `Task`, `TaskUpdate`, `Milestone`, `Decision`, `Blocker`) 트랜잭션은 자기
  aggregate 안에 닫힌다. 예외는 workspace 라벨 발급이 task/project 생성 트랜잭션에 참여하는 것과,
  `minsu`가 연 트랜잭션에서 `TaskDraftApplier`로 `Task`를 변경하는 것이다([ADR-0015](../adr/0015-minsu-async-task-draft-generation.md)). 엔티티 간 참조는 JPA 연관이 아니라
  식별자로 유지한다.

## outbox

append-only outbox 발행 로그 공용 모듈이다(ADR-0008).

사용자 확정 액션과 outbox 발행은 같은 트랜잭션에 속한다. 멱등키는 서버가 생성하며
모바일 클라이언트에 `Idempotency-Key`를 요구하지 않는다.
Signal·task 생성과 Signal 처리의 기본 이벤트 계약은 다음과 같다. `aggregate_id`는 해당 Signal 또는 task의 ID다.

| event_type | issued_by | aggregate_type | payload |
| --- | --- | --- | --- |
| `signal.created` | worker | signal | `{}` |
| `task.created` | api-server | task | `{ "origin_type": "manual\|signal", "origin_signal_id": null\|"<signal_id>" }` |
| `signal.converted_to_task` | api-server | signal | `{ "task_id": "<task_id>" }` |
| `signal.dismissed` | api-server | signal | `{}` |

비동기 draft가 실제로 바뀔 때만 task 변경·원장 종료와 같은 트랜잭션에 `task.draft_generated`를 남긴다.
aggregate는 `task`, aggregate_id는 task ID, payload는 `{}`, 멱등키는
`task.draft_generated:{task_id}`다. worker는 최신 task를 hydrate하며 원장 terminal을 기다리지 않는다.
생성 성공이어도 값이 같거나 task를 바꾸지 않는 종료면 이벤트를 생략한다.
worker-first 활성화 조건은 [운영 준비 대장](../prod-readiness-ledger.md#minsu-활성화와-복구)을 따른다.

- `outbox_events` 엔티티·리포지토리·마이그레이션을 소유한다.
- 쓰기 public API `OutboxAppender`: 호출자 트랜잭션에 합류해(`Propagation.MANDATORY`)
  `{workspace_id, aggregate_type, aggregate_id, event_type, payload}`를 append한다. 기존 `append()`는
  멱등키 `"{event_type}:{aggregate_id}"`를 조립한다. 반복 변경에는 호출자가
  `appendWithIdempotencyKey()`로 변경 건별 키를 전달한다. 두 경로 모두
  `idempotency_key UNIQUE` + `ON CONFLICT DO NOTHING`으로 같은 키를 dedup한다(SD-3).
- task producer 계약(`MOM-1002`): `task.created`는 기존 생성 이벤트와
  `task.created:{taskId}` 키를 유지한다. 살아 있는 task의 `title`, `description`, `status`,
  `priority`, `role`, `milestone_id`, `assignee_id`, `due_date` 중 실제 값이 바뀌면
  `task.updated`를 빈 payload `{}`와 `task.updated:{taskId}:{changeUUID}` 키로 남긴다.
  완료 기준만 바뀌거나 비교 대상 값이 같으면 발행하지 않는다. soft delete에는 빈 payload의
  `task.deleted`와 `task.deleted:{taskId}` 키를 남긴다. task 변경과 이벤트 저장은 같은
  트랜잭션이다.
- task consumer 계약(`MOM-0956`): envelope의 `aggregate_id`를 task ID로 사용한다.
  `task.created`·`task.updated`는 공유 DB에서 삭제 상태를 포함한 **최신** task를 조회해
  투영한다. `deleted_at`이 있거나 원본 행을 찾을 수 없으면 검색 문서와 source-ref를 삭제
  상태로 수렴시킨다. `task.deleted`도 ID만으로 삭제 처리하며 살아 있는 원본 조회에
  의존하지 않는다. 따라서 삭제 뒤 과거 생성·수정 이벤트를 재처리해도 문서를 복원하지 않는다.
  같은 최종 값을 재요청하면 수정 이벤트를 생략하지만, HTTP 요청별 멱등성은 보장하지 않는다.
  A→B→C 뒤 B 요청을 다시 보내 실제로 B로 바뀌면 새 UUID의 이벤트가 발행된다.
- 조회 public API `OutboxEventReader`(`readAfter`, `latestIdBefore`, `findById`)와
  `OutboxEventView`([push 운영](../prod-readiness-ledger.md#signal-push-운영)). watermark 이후 event를 id
  오름차순으로 읽되 DB 시계 기준 안전 지연을 지나지 않은 첫 event에서 멈춘다(prefix-cap).
  watermark 등 소비 상태 관리는 이 모듈이 아니라 consumer가 소유한다.
- `issued_by`는 이 서버가 발행하는 이벤트만 다루므로 `api-server`로 고정한다. worker가 발행하는
  `signal.created`는 worker 쪽 책임이다. dev Signal 생성 도구만 `api-server`로 같은 이벤트를 기록한다.
- 다른 도메인 모듈을 참조하지 않는다. 도메인 producer가 쓰기 public API를, `notification`이
  조회 public API를 사용한다.
- outbox 소비(consumer)의 상태 관리·재시도·DLQ는 이 모듈의 책임이 아니다. worker의 projection 소비는
  ADR-0008, api-server의 `signal.created` notification 소비(`notification` 모듈)는 ADR-0009를
  따르며 둘 다 별도 구현이다.

## source·context

외부 ingest·curation은 worker 책임이다. source는 연결·자격 증명·참조 검증을 소유하며,
운영 `SourceRefWriter`와 dev 전용 `DevSourceRefWriter`를 구분한다.

내부는 변경 이유로 두 nested 논리 모듈로 나눈다(MOM-0887). `connection`은 소스 연동
(`source_connections`)과 자격 증명(`source_credentials`), 그리고 연동을 만드는 provider OAuth
설치 흐름을 함께 소유한다. 자격 증명이 OAuth 콜백에서만 만들어지고 갱신되므로 둘을 같은 경계에 둔다.
`ref`는 source-ref(`source_refs`)의 저장·조회·검증을 소유하고, 연동 없이 붙여넣은 주소도 다루므로
`connection`에 의존하지 않는다. 두 경계 사이에는 코드 참조가 없다.

`signal`과 같은 방식으로 공개 계약은 모듈 root에 두고 구현만 nested 패키지에 은닉한다. 다른 상위
모듈은 nested application module을 참조할 수 없으므로 이 배치가 성립한다. `connection` 아래
`oauth` 서브패키지는 provider 규격·토큰 취급이라는 별도 관심사라 유지하며, Java package-private이
서브패키지까지 뻗지 않아 연동 엔티티·리포지토리는 부득이 public으로 남는다(`mobile`의 `dto`
서브패키지와 같은 제약). OAuth 콜백 컨트롤러는 `presentation`에 그대로 둔다.

context의 `EntityRelationReader`·`EntityRelationWriter`는 연결을 조회·생성·해제한다.
다른 도메인 모듈에 의존하지 않으며, 두 엔티티의 workspace 일치와 인가는 조합하는 `web`이 확인한다.
본문 hydrate도 소비자가 source 등의 public API로 수행한다. 스키마 소유권은
[ADR-0019](../adr/0019-prod-schema-ownership-transfer.md)를 따른다.

## Signal·notification·Minsu 내부 경계

- Signal의 query/action은 nested 모듈이고 공개 계약은 root에 둔다. action은 `SignalReader`로
  읽는다. facade의 멱등·충돌 처리와 executor의 원자 쓰기를 분리한다. backing과 action의 소유권은
  [ADR-0007](../adr/0007-signal-backing-and-module-boundary.md)·
  [ADR-0011](../adr/0011-signal-evidence-and-task-draft-contract.md)을 따른다.
- notification의 device·consume·dispatch·fcm은 변경 이유가 다른 nested 모듈이다.
  consume → dispatch, consume·dispatch → device, dispatch → fcm 방향으로 협력한다.
  소비 replay의 중복 방지 기록은 dispatch가 소유하고 SDK 타입은 fcm 밖으로 노출하지 않는다.
  설치 HTTP는 mobile이 `PushDeviceRegistrar`에 위임한다. 전달·복구 계약은
  [운영 준비 대장](../prod-readiness-ledger.md#signal-push-운영)에 모은다.
- minsu의 draft는 준비·원장·실행·반영·상태 조회를, llm은 provider 중립 계약·설정·model 선택을
  소유한다. Google adapter는 llm 내부에 두고 다른 Gradle 모듈에는 root 공개 계약만 노출한다.
  [아래 생성·반영 경계](#minsu-생성반영-경계)와 [ADR-0015](../adr/0015-minsu-async-task-draft-generation.md)를 따른다.

### Dev Signal 생성 경계

`signal.dev`는 `@DevOnly`를 통해 prod에는 endpoint를 등록하지 않는다. 호출 시 유효한 Bearer
access token이 필요하지만 workspace 멤버십과 role은 별도로 검사하지 않는다.
source-ref·Signal·evidence·`signal.created`를 한 트랜잭션으로 저장한다. HTTP 성공은 commit이며
push 성공을 기다리지 않는다. 전용 insert 경로를 사용하고 `@Immutable` 읽기 엔티티를 재사용하지 않는다.
`DevSourceRefWriter`와 `OutboxAppender`는 호출자 트랜잭션에 참여한다.
worker도 같은 backing과 빈 payload·`signal.created:{signal_id}` 멱등키를 사용하며 producer만 바뀐다.
생산 시작 전 **MOM-0916**의 UUID 생성 방식·FK·삭제 정책 재검토를 완료해야 한다
([worker Signal 생산 전 확인](../prod-readiness-ledger.md#worker-signal-생산-전-확인)).
도구 제거는 MOM-0944가 소유하고, worker 입력의 `file` 등 proto 정합성 검증도 별도로 남는다.

### Minsu 생성·반영 경계

공개 API는 [SignalTaskDraftGenerator](../../modules/minsu/src/main/java/works/momens/server/minsu/SignalTaskDraftGenerator.java)의
`prepare`·`enroll`과 `TaskDraftStatusReader`다. provider/model·prompt·SDK는 공개하지 않는다.

- Signal 존재·권한·기존 action을 먼저 확인하고 dismiss·replay·충돌에서는 생성하지 않는다.
  신규 convert만 쓰기 트랜잭션 밖에서 prepare하고 반환된 `PreparedTaskDraft`를 그대로 enroll한다.
  모드 판정은 minsu가 한 번 수행하며 signal이 flag로 분기하지 않는다.
- 동기 prepare는 생성/fallback, 비동기는 fallback만 준비한다. enroll은 동기 no-op을 포함해
  `MANDATORY`이며 비동기 task·action·pending 원장·기존 outbox 두 건은 함께 commit한다.
  원장 실패는 convert 전체 rollback이다. commit 뒤 응답 유실은 replay이며 재적재하지 않는다.
  task·원장 unique는 동시 동기 요청의 중복 모델 호출까지 막지 않는다.
- 입력은 Signal title/type/description/impact와 의미 있는 evidence target/change/impact다.
  evidence는 sort_order·source_ref_id 순 최대 10건이며 저장/상세 API의 개수 제한이 아니다.
  source 원문·snippet·URL·작성자·provider metadata·리소스 식별자는 모델에 넘기지 않는다.
  description/impact/evidence가 모두 비면 title/type만으로 추론하지 않는다.
- system instruction과 고정 필드 JSON을 분리하고 외부 문자열을 prompt에 보간하지 않는다.
  출력 전체를 검증하며 role/priority가 무효면 전체 fallback으로 바꾼다. title은 trim 후 Java 길이
  15 이하로 정규화하되 surrogate pair를 자르지 않으며 빈 모델 title은 Signal title로 대체한다.
  fallback은 정규화된 Signal title·pm·medium이다. 생성 실패 자체는 새 public 5xx를 만들지 않는다.
- convert 입력 snapshot과 **실제 저장한** title/role/priority baseline, 두 deadline을 원장에 저장한다.
  실행 시 Signal 재조회나 baseline 재계산을 하지 않는다. description/impact는 무절단 저장하며
  길이 CHECK를 추가하지 않는다(MOM-0817). terminal snapshot 정리는 MOM-0912 미결정이다.
- `TaskDraftApplier`는 task row lock 후 세 baseline 값 모두 같을 때만 전체를 반영한다.
  하나라도 다르면 user_edited, 삭제·미존재면 task_gone으로 원장만 닫는다. updated_at을 비교하지
  않아 다른 필드 편집·원래 값으로 되돌린 편집은 감지하지 못한다.
  task 변경·원장 종료·outbox는 같은 transaction manager에 참여하며 REQUIRES_NEW/조기 commit은 없다.
  반영 후 token/deadline 조건 실패도 부분 commit하지 않는다.
- 신규 응답은 enroll 결과로 비동기 fallback·generating 또는 동기 ready를 반환한다.
  replay·상세는 **권한 → 원장 → task** 순서다. READ COMMITTED에서 ready를 읽은 뒤 최신 task를
  읽으며 AI title·generating 조합은 허용한다. 격리 수준/조회 조립 변경 시 이 보장을 다시 검증한다.
  원장 없음·completed·DB read deadline 초과는 ready이며 현재 flag로 과거 상태를 재해석하지 않는다.
  앱 표시와 deadline 예외는 [모바일 명세](../spec/mobile-api.md), 복구는
  [운영 준비 대장](../prod-readiness-ledger.md#minsu-활성화와-복구)을 따른다.

스키마·상태 전이·잠금의 구현 정본은 [마이그레이션](../../modules/minsu/src/main/resources/db/migration/)과
[TaskDraftGenerationLedger](../../modules/minsu/src/main/java/works/momens/server/minsu/draft/ledger/TaskDraftGenerationLedger.java)다.
