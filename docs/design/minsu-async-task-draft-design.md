# Minsu task draft 생성 계약

동기·비동기 생성의 현재 계약과 운영 복구 조건을 다룬다. 설계 배경·대안은
[ADR-0014](../adr/0014-minsu-task-draft-module-and-llm-boundary.md)·
[ADR-0015](../adr/0015-minsu-async-task-draft-generation.md), 완료된 설계·실측·구현 이력은
MOM-0803·0806·0810 및 후속 구현 작업에 있다. 변경 전 원문은 MOM-1057의 고정 커밋 링크에 보존했다.
HTTP 필드는 [OpenAPI](../spec/openapi.md), 앱 연동은 [모바일 API](../spec/mobile-api.md)를 따른다.

## 준비와 적재

공개 계약은 [SignalTaskDraftGenerator](../../modules/minsu/src/main/java/works/momens/server/minsu/SignalTaskDraftGenerator.java)의
`prepare`·`enroll`과 `TaskDraftStatusReader`다. provider/model·prompt·SDK 타입은 공개하지 않는다.

1. signal이 존재·권한과 기존 action을 확인한다. dismiss·replay·충돌에서는 evidence 조회와 생성을 하지 않는다.
2. 신규 convert는 쓰기 트랜잭션 밖에서 `prepare(input)`을 호출한다.
   동기 모드는 LLM 생성 또는 fallback, 비동기 모드는 LLM 없이 fallback을 준비한다.
3. `PreparedTaskDraft`를 그대로 convert 트랜잭션의 `enroll`에 넘긴다.
   minsu가 한 번 판정한 모드를 내부 타입으로 보존하므로 signal은 활성 여부로 분기하지 않는다.
4. 비동기면 task·action·생성 원장 `pending`·기존 outbox 두 건을 함께 커밋한다.
   동기면 enroll은 no-op이다. **두 모드 모두 enroll은 `MANDATORY` 트랜잭션을 요구한다.**

원장 insert 실패는 convert 전체를 롤백하는 fail-closed 계약이다. 다음 요청은 신규 convert다.
커밋 후 응답만 유실된 경우는 replay이며 원장을 다시 적재하지 않는다. `signal_actions`의 Signal
unique와 원장의 task unique가 중복 task·원장을 막지만, 동시 동기 요청의 중복 모델 호출까지 막지는 않는다.

## 공통 생성 입력과 결과

- 입력은 Signal title/type/description/impact와 evidence의 target/change/impact다.
  의미 있는 evidence를 `sort_order`, `source_ref_id` 순서로 최대 10건 읽고 generator도 trim·빈 항목
  제외·10건 상한을 적용한다. 저장된 evidence나 상세 API의 개수 제한이 아니다.
- source 원문·snippet·URL·작성자·provider metadata와 Signal/workspace/project/user 식별자는
  모델 입력에 넣지 않는다. description/impact/evidence가 모두 비면 title/type만으로 추론하지 않는다.
- system instruction과 고정 필드 JSON 입력을 분리한다. 외부 문자열을 prompt에 직접 보간하지 않는다.
- candidate·finish reason·text·JSON·role/priority를 검증한다. title은 trim 후 Java 길이 15 이하로
  정규화하되 UTF-16 surrogate pair를 자르지 않는다. 빈 모델 title은 Signal title로 대체한다.
- role/priority 무효 시 모델 값과 fallback을 섞지 않고 draft 전체를 fallback으로 바꾼다.
  fallback은 정규화된 Signal title, `pm`, `medium`이다. 생성 실패는 새 public 5xx를 만들지 않으며
  인증·권한·action 충돌 등 기존 오류는 유지한다. 비동기 원장 적재 실패는 위 fail-closed 예외다.
- 정적 설정 무효는 기본값으로 clamp하지 않는다. 앱은 기동하고 설정 오류·지표를 남긴다.
  동기 요청은 fallback을 사용한다. 비활성·설정 무효·입력 부족이면 provider client를 만들지 않는다.
- Google client는 provider 경계에서 지연 생성하고 첫 성공만 캐시한다. ADC/client 생성 실패는
  캐시하지 않으므로 다음 호출이 다시 시도한다. 성공한 client는 context 종료 시 닫는다.

## 원장과 반영

minsu가 `minsu_task_draft_generations`를 소유한다. 컬럼·제약은
[마이그레이션](../../modules/minsu/src/main/resources/db/migration/), 상태 전이·잠금은
[TaskDraftGenerationLedger](../../modules/minsu/src/main/java/works/momens/server/minsu/draft/ledger/TaskDraftGenerationLedger.java)가 기준이다.
prod 스키마도 [ADR-0019](../adr/0019-prod-schema-ownership-transfer.md)에 따라 서버 Flyway가 소유한다.

- convert 시점의 입력 snapshot을 저장하고 실행 시 Signal을 다시 hydrate하지 않는다.
  description/impact는 잘라 저장하거나 길이 CHECK를 추가하지 않는다(MOM-0817).
  terminal 이후 snapshot 보존 기간·정리 주체는 **MOM-0912의 미결정**이다.
- task에 실제로 쓴 title/role/priority를 baseline 값으로 저장한다. 나중에 fallback 규칙으로
  재계산하지 않는다. snapshot 정리 정책과 baseline은 별개다.
- `read_deadline_at`과 `apply_cutoff_at`도 적재 시 계산해 저장한다. 설정 변경으로 과거 작업의
  `ready`가 다시 `generating`이 되지 않는다.
- task에는 처음부터 유효한 draft가 있다. 비동기 실패 시 fallback을 유지한다. 형식의 유효성은
  보장하지만 모델 결과가 fallback보다 의미적으로 낫다는 보장은 아니다.

### 사용자 수정과 원자성

[TaskDraftApplierImpl](../../modules/project/src/main/java/works/momens/server/project/task/internal/TaskDraftApplierImpl.java)은
row lock 후 baseline을 비교하고 엔티티를 변경한다. 다음 조건을 지킨다.

- 살아 있는 task의 title/role/priority **세 값 모두** baseline과 같아야 전체 draft를 반영한다.
  하나라도 다르면 전체를 폐기하고 `user_edited`, 삭제·미존재면 `task_gone`으로 닫는다.
- `updated_at`은 비교하지 않는다. purpose·완료 기준만 바꾸거나 세 값을 원래대로 돌린 편집은
  감지하지 못한다. 필드 일부만 AI 결과로 반영하는 혼합은 하지 않는다.
- task 변경·원장 terminal 전이·필요한 outbox는 **같은 트랜잭션**이다. project 반영 API는 호출자의
  transaction manager에 참여하며 `REQUIRES_NEW`나 조기 commit을 사용하지 않는다.
- 반영 후 원장 token/deadline 조건이 맞지 않으면 부분 커밋하지 않는다. 반영 전에 판정한
  `user_edited`·`task_gone`은 task를 바꾸지 않고 원장만 닫는 정상 경로다.

### Projection 이벤트

반영 값이 baseline과 실제로 달라질 때만 같은 트랜잭션에 다음 이벤트를 남긴다.

```text
event_type    = task.draft_generated
aggregate     = task
aggregate_id  = task_id
payload       = {}
idempotency_key = task.draft_generated:{task_id}
```

worker는 최신 task를 hydrate한다. 생성 성공이어도 값이 같으면 원장은 `generated`로 닫고 이벤트는
생략한다. `user_edited`·`task_gone`·`retry_exhausted` 등 task를 바꾸지 않는 종료도 발행하지 않는다.

기존 `task.created`가 fallback을 먼저 투영할 수 있으므로 후속 이벤트가 필요하다. 모르는 이벤트에서
watermark를 전진시키는 worker에 producer부터 활성화하면 나중에 재소비되지 않는다.
**worker-first 배포와 end-to-end projection 검증(MOM-0823)**이 기본 활성화 조건이다.
그 대안은 미지원 이벤트에서 watermark를 전진시키지 않는 구현의 검증 또는 명시적 cutover/backfill
절차가 있는 경우뿐이다. worker가 minsu 원장 terminal을 기다리도록 결합하지 않는다.

## 응답 읽기 순서와 deadline

신규 convert는 enroll 결과를 사용한다. 비동기 적재 시 원장을 다시 읽지 않고 fallback title과
`generating`, 동기면 `ready`를 반환한다.

replay·task 상세는 **권한 확인 → 원장 상태 → task 본문** 순서다. 원장이 없거나 completed면
`ready`, 미종료라도 DB 시계가 `read_deadline_at`을 넘으면 읽기 투영으로 `ready`다.
현재 feature flag로 과거 task 상태를 해석하지 않는다.

- `READ COMMITTED`에서 원장 `ready`를 먼저 읽으면 그 뒤 task는 정상 반영 완료 값 또는 더 최신 값이다.
- 원장 `generating` 직후 생성이 끝나 AI title과 `generating`이 함께 나가는 것은 허용한다.
  역순 조회는 오래된 title과 `ready`가 함께 나가 갱신을 놓치므로 금지한다.
- 격리 수준이나 조회 조립 방식을 바꿀 때 순서만으로 정합성을 보장한다고 가정하지 않는다.
- 상태는 convert·task 상세에서 확인한다. 목록·보드에는 노출하지 않으며 상세는 무거운 조회라
  짧은 간격 polling에 적합하지 않다. 표시·재조회 간격·캐시 무효화는 앱이 결정한다.

```text
apply_cutoff_at  = 적재 시각 + deadline - margin
read_deadline_at = 적재 시각 + deadline
```

scheduler가 멈춰도 읽기 투영은 작동한다. 반영은 cutoff 이후 허용하지 않으며 정상 종료 시 `ready`와
함께 반환한 title은 최종 값이다. 다만 **deadline 경계의 절대 보장은 아니다.** PostgreSQL `now()`는
트랜잭션 시작 시각이고, cutoff 전에 평가한 트랜잭션이 margin보다 오래 지연되면 읽기 deadline 뒤에
커밋해 이미 `ready`로 알린 title이 바뀔 수 있다. margin은 이 창을 줄이는 장치다.
조회가 쓰기 잠금을 잡아 이 창을 닫는 방식은 채택하지 않았다.

## Claim·timeout·retry·복구

- claim마다 새 token과 DB 시간 기준 lease를 저장한다. 결과 기록·재시도 예약·terminal 전이는
  token이 일치해야 한다. 만료 뒤 재claim한 다른 실행이 있으면 이전 결과는 stale로 버린다.
- 모델 호출은 DB 트랜잭션 밖에서 한다. lease 만료·재시작 때문에 중복 **실행**은 가능하다.
  token으로 직렬화하는 결과 기록과 모델 호출 횟수를 혼동하지 않는다.
- convert 커밋 후 종료되면 pending을, claim 후 종료되면 만료 lease를 회수한다.
  반영 트랜잭션 도중 종료되면 미커밋 변경을 롤백하고 lease 만료 후 다시 처리한다.
- 동기 SDK timeout은 8초이며 총 시도는 1회다. SDK call timeout은 ADC 탐색·client 생성·token
  refresh 전체를 덮지 않으므로 실제 provider 관측과 요청 점유는 이를 넘을 수 있다.
- 비동기는 SDK timeout과 별도로 ADC부터 provider까지 attempt 전체의 wall-clock 상한과 실행 동시성
  상한을 둔다. timeout wrapper는 대기를 끝내고 결과를 버릴 뿐 interrupt를 무시하는 호출을
  강제 종료하지 못한다. hung 호출이 모든 슬롯을 소진하면 관측 후 Pod 재시작으로 복구한다.
  원장은 남으므로 pending·만료 lease를 이어 처리한다.

| 내부 결과 | 처리 |
| --- | --- |
| generated / generated_title_fallback / generated_truncated | 성공 반영 |
| timeout / provider_error / invalid_response / invalid_output | 제한 재시도, 상한이면 retry_exhausted |
| insufficient_context | terminal |
| invalid_config | claim 이후 무효화 경합에서 terminal. claim 전 무효면 claim하지 않음 |
| disabled | 신규 적재 없음. 기존 원장은 claim하지 않고 보존 |

`provider_error`는 일시 오류와 ADC/IAM/project/quota 문제를 세분화하지 않고 모두 제한 재시도한다.
설정을 고친 뒤 terminal 과거 건을 소급 생성하는 동작은 없다.

설정의 정본은 [MinsuAsyncProperties](../../modules/minsu/src/main/java/works/momens/server/minsu/draft/config/MinsuAsyncProperties.java)와
[application.yml](../../app/src/main/resources/application.yml)이다. provider 20초·attempt 30초·lease 60초,
백오프 10/30/120초·최대 4회·동시성 4, deadline 1시간·margin 5분은 **MOM-0824 실측 전 잠정값**이다.
동기 8초의 실측을 비동기 운영값 확정 근거로 사용하지 않는다.

```text
attempt timeout < lease < 적재부터 apply cutoff까지의 시간 < 적재부터 read deadline까지의 시간
```

lease는 갱신하지 않는다. drain은 배치 결과 기록까지 기다리는 fixed-delay 패스이므로 주기는 실제
claim 간격이 아니다. 빈 슬롯이 생겨도 다음 패스까지 기다리며 가장 느린 attempt가 배치를 지연시킨다.
스케줄링 인프라는 app이 소유하고, drain·push·지표·원장 종료가 서로 막히지 않도록 공통 scheduler
설정도 함께 확인한다.

## 관측

지표 이름·태그·집계 의미는 [지표 인벤토리](metrics-inventory.md)에 모은다.
provider Observation은 lazy client 생성부터 응답까지 포함한다. provider에 진입하지 않은 fallback은
요청 counter만 남긴다. metric/tag/span/event에 식별자·prompt·입력·응답 원문을 넣지 않는다.
로그에도 source 원문·URL·credential·token을 남기지 않는다.

- 미종료 건수/최대 나이, 만료 lease, deadline 초과, heartbeat와 지표 snapshot age를 함께 본다.
  pending만 보면 processing에서 멈춘 작업을 놓친다.
- retry_exhausted의 사유 구성비, user_edited, claim 대기·전체 생성 지연으로 품질과 속도를 판단한다.
- provider 호출 수 / 종료 건수는 중복 실행 비용의 추정치이며 진행 중 작업이 분모에서 빠진다.
- reclaim과 stale이 함께 오르면 lease 길이·갱신을 검토한다. 적재 실패율은 fail-closed 영향을 본다.
- deadline 읽기 투영은 정상적으로 0이어야 한다. 경보 기준·구현은 **MOM-0832**에서 다룬다.

## 활성화와 rollback

| 설정 축 | 끄면 |
| --- | --- |
| enroll | 신규 convert는 동기 경로. 기존 원장은 유지 |
| drain | 생성 실행 중단. 읽기 deadline과 만료 원장 종료는 유지 |
| provider | 신규 적재 없음. 기존 원장도 claim하지 않음 |

provider 설정이 무효하면 비동기 적재는 하되 claim하지 않는다. 기존 작업을 실패로 닫지 않고 설정
복구 후 처리한다. 롤링 배포에서 활성 pod가 적재한 작업은 다른 활성 pod도 처리할 수 있다.
설정 rollback 후 기존 pending은 기본적으로 계속 처리하며, 별도 중단이 필요할 때 drain을 끈다.
원장/조회 기능이 없는 이전 바이너리로 rollback하면 `draft_status` 필드 자체가 없는 이전 계약이다.
활성화 이전 task에는 원장이 없으며 소급 생성하지 않는다.

prod 활성화 전에는 다음을 확인한다. 이번 문서 정리가 완료를 의미하지 않는다.

- dev 재측정으로 timeout·lease·deadline·동시성 검증: **MOM-0824**.
- `task.draft_generated` worker 소비 배포와 E2E projection: **MOM-0823**.
- `draft_status`를 처리하는 앱 배포.
- 데이터 레지던시 정책: model/location catalog는 기술적 호출 가능 목록이지 보안·법무 허용 목록이
  아니다. location 미지정 시 기본 global이 적용되므로 prod 허용 지역과 명시 강제 여부를 결정하기
  전에는 활성화하지 않는다.
- snapshot 보존 기간·정리 주체: **MOM-0912**. 이미 결정된 무절단 저장과 구분한다.

### 만료 원장과 수동 종료

반영 창을 지난 pending과 lease가 만료된 processing은 주기 작업이 `deadline_exceeded`로 닫는다.
살아 있는 processing lease는 결과 기록 경로가 처리하므로 먼저 닫지 않는다. 이 정리는 drain
설정과 독립적이며 행 잠금·엔티티 변경으로 감사 시각과 종료 지표를 남긴다.

반영 창 안의 작업까지 즉시 종료해야 할 때만 아래 수동 절차를 사용한다.
**drain을 먼저 끄고 대상을 확인한다. 종료 후 되살리지 않는다.**

```sql
-- 1. 대상 확인
SELECT status, count(*), min(created_at) AS oldest
  FROM minsu_task_draft_generations
 WHERE status <> 'completed'
 GROUP BY status;

-- 2. claim CHECK와 감사 시각을 함께 갱신
UPDATE minsu_task_draft_generations
   SET status = 'completed',
       completion_reason = 'operationally_closed',
       claim_token = NULL,
       lease_expires_at = NULL,
       updated_at = NOW()
 WHERE status <> 'completed';
```

설정 오류와 구분하기 위해 `invalid_config`를 재사용하지 않는다. 이미 claim한 호출의 늦은 결과는
token 불일치로 버려진다. 수동 SQL은 JPA Auditing을 거치지 않으므로 `updated_at`을 직접 갱신한다.

## 적용된 migration의 과거 참조

Flyway checksum을 보존하기 위해 적용된 SQL의 주석은 바꾸지 않는다. SQL 주석의 이전 절 번호는
[변경 전 Minsu 설계](https://github.com/momens-works/momens-server/blob/93a8f733ffdfa5220348be321455ece5dd345d9e/docs/design/minsu-async-task-draft-design.md)를 가리킨다.
현재 계약은 이 문서 본문을 따른다.
