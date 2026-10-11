# MOM-0810 Minsu 풍부한 task draft 비동기 생성 설계

작성일: 2026-08-01

상태: 확정 설계 (구현 전)

관련 결정: [ADR-0011](../adr/0011-signal-evidence-and-task-draft-contract.md),
[ADR-0015](../adr/0015-minsu-async-task-draft-generation.md),
[ADR-0014](../adr/0014-minsu-task-draft-module-and-llm-boundary.md)(0015가 supersede),
[ADR-0008](../adr/0008-outbox-worker-projection-boundary.md)

관련 문서: [MOM-0803 동기 task draft 상세 설계](minsu-signal-task-draft-design.md),
[Signal push demo 설계](signal-push-demo-design.md)

## 1. 목적

`POST /api/mobile/signals/{signalId}/actions/convert-to-task`는 현재 Minsu task draft
생성을 동기로 기다린다. 모델 생성의 긴 tail latency가 사용자 요청 시간을 그대로 늘리므로,
즉시 사용할 수 있는 기본 draft를 먼저 반환하고 풍부한 생성 결과를 비동기로 결합하는 구조를
설계한다.

이 문서는 설계만 확정한다. 구현은 후속 티켓으로 분리한다.

### 이 문서가 다루는 것과 다루지 않는 것

동시성 설계는 문서로 닫히지 않는다. 인터리빙은 계속 만들어낼 수 있고 산문으로는 검증할 수
없다. 따라서 경계를 명시한다.

- **이 문서가 정한다** — 상태와 전이, 어떤 조건이 correctness requirement인지, 무엇을 보장하고
  무엇을 보장하지 않는지, 모듈 경계와 의존 방향, 공개 계약의 모양.
- **구현 티켓이 정하고 테스트로 검증한다** — 컬럼·인덱스·SQL, 타임아웃과 lease·백오프·margin
  값, 격리 수준 검증, 구체적 인터리빙 시나리오 테스트.

문서에 없는 인터리빙이 발견되면 그것은 이 문서의 결함이 아니라 구현 티켓의 테스트 항목이다.
7.2절과 8.6절이 정한 보장의 **범위**만 지켜지면 된다.

## 2. 범위

### 포함

- draft 생성 상태와 전이 정의
- 비동기 생성 trigger 시점과 결과 저장 소유권
- 프로세스 재시작에도 유실되지 않는 after-commit 전달 방식
- 중복 실행 멱등성, 실패 복구, 사용자 수정과 AI 결과의 경합 방지
- 모바일 결과 확인 방식과 API 계약 영향
- 비동기 작업의 timeout·retry·관측 정책
- 동기 계약에서의 단계적 전환 방안
- 지연 예산 확대 후 Agentic RAG 성립 여부 검토
- 앱·ingress·서버 3구간 timeout 예산 정리

### 제외

- 이 티켓에서의 비동기 생성 구현
- 별도 마이크로서비스 분리
- prod 활성화
- MOM-0806에서 확정한 동기 호출 8초 timeout 구현 변경

## 3. 현재 동기 경로와 문제

`SignalActionServiceImpl.convertToTask`의 현재 순서는 다음과 같다.

```text
1. SignalReader.findLive + workspace membership 확인
2. SignalActionRepository.findBySignalId
3. 기존 action이면 replay/충돌 반환 (generator 미호출)
4. evidence 조회 (의미 있는 앞선 10건)
5. SignalTaskDraftGenerator.generate       ← 동기 LLM 호출, 쓰기 트랜잭션 밖
6. SignalActionExecutor.convert            ← tasks + signal_actions + outbox 2건 원자 저장
```

5번이 사용자 요청 시간에 그대로 더해진다. MOM-0806 로컬 측정에서 warm provider 구간은
p50 1,258ms이지만 p95 5,960ms / max 6,161ms로 tail 변동이 컸다. 30건 중 19건이 1,500ms
미만인데 3건이 5,000ms 이상이었다.

구조적 문제는 세 가지다.

**첫째, 지연 예산이 응답 시간에 묶여 있다.** 8초 timeout은 원탭 action의 사용자 체감을
기준으로 정한 값이라, 검색·재검토를 반복하는 생성 구조(Agentic RAG 등)를 이 예산 안에
넣을 수 없다. 품질을 올리려면 예산을 늘려야 하고, 예산을 늘리면 동기 경로에서는 사용자가
그만큼 기다린다.

**둘째, 8초 timeout이 실제 상한이 아니다.** `HttpOptions.timeout`은 SDK가 OkHttp `Call`을
실행하는 구간에만 걸린다. 그 앞의 ADC 탐색·client 생성·`refreshIfExpired()` token 갱신과
최초 client 생성을 기다리는 동시 요청은 이 8초 밖이다. MOM-0806의 15.2절 검증에서
`MOMENS_MINSU_LLM_TIMEOUT=10ms`로도 전체 API 889ms / provider observation 705ms가 나온
것이 이 구간의 존재를 보여준다.

**셋째, 품질과 무관하게 fallback 비율이 지연에 연동된다.** timeout에 걸리면 고정 fallback
(Signal title / `pm` / `medium`)이 저장되고, 사용자는 그것이 모델 결과인지 fallback인지
알 수 없다. 지연이 큰 Signal(evidence가 많고 context가 긴 Signal)일수록 fallback 확률이
높은데, 그런 Signal이야말로 모델 생성의 가치가 큰 쪽이다.

## 4. 3구간 timeout 예산

현재 세 구간의 값은 다음과 같다.

| 구간 | 값 | 출처 | 성격 |
| --- | --- | --- | --- |
| 앱 | 10초 | OkHttp 기본값 | 명시적으로 정한 값이 아님 |
| ingress | 60초 | ingress-nginx 기본값 | 명시적으로 정한 값이 아님 |
| 서버 LLM 호출 | 8초 | MOM-0806 팀 합의 | 유일하게 근거를 두고 정한 값 |

앱 10초와 ingress 60초는 둘 다 프레임워크 기본값이며 Momens가 예산으로 설계한 값이 아니다.
서버 8초만 측정에 근거한 합의값이다.

이 조합에서 **앱이 서버보다 먼저 끊는 창**이 존재한다. 서버 8초는 OkHttp `Call` 구간에만
걸리고 그 앞단(ADC 탐색·client 생성·token 갱신)과 뒷단(fallback draft 생성, task/action/outbox
저장, 커밋, 직렬화)은 측정되지 않았다. cold 최초 호출에서 앞단이 2초를 넘으면 전체 요청이
앱의 10초를 넘길 수 있고, 그때 앱은 요청을 실패로 처리하지만 서버는 task를 정상 생성한다.

이 불일치의 실제 사용자 노출은 다음과 같다. 앱이 끊어도 서버 트랜잭션은 커밋되므로 task는
생성된다. 사용자가 재시도하면 `signal_actions UNIQUE(signal_id)`에 걸려 replay 경로로
들어가 200과 기존 task를 받는다. 즉 **중복 task는 생기지 않고**, 사용자는 최초 시도에서
실패를 본 뒤 재시도에서 성공을 본다. 데이터 정합성 문제는 아니지만 체감은 나쁘다.

미측정으로 남은 구간은 다음과 같으며 dev 재측정 항목에 포함한다.

- ADC 탐색과 SDK client 최초 생성
- 만료 credential의 token refresh가 발생하는 호출
- 최초 client 생성을 기다리는 동시 요청의 대기
- fallback draft 생성부터 커밋·직렬화까지의 뒷단
- ingress timeout과 서버 timeout의 선후 관계

비동기 전환은 이 문제의 상당 부분을 구조적으로 없앤다. LLM 호출이 요청 경로에서 빠지면
동기 구간은 DB 쓰기만 남아 수십 ms 수준이 되고(MOM-0806 측정에서 `전체 API - provider`는
p50 43ms / max 117ms), 앱·ingress timeout과 경쟁하지 않는다.

## 5. after-commit 전달 방식

비동기 생성은 convert 트랜잭션이 **커밋된 뒤에** 시작해야 한다. 커밋 전에 시작하면 롤백된
task에 대해 모델을 호출하게 되고, 결과를 쓸 대상이 없다. 동시에 프로세스가 재시작해도
작업이 유실되지 않아야 한다.

### 5.1 후보

**(a) `@Async`** — 가장 단순하지만 작업이 JVM 힙의 스레드풀 큐에만 존재한다. 배포·OOM·
Pod eviction으로 프로세스가 내려가면 큐에 있던 작업과 실행 중이던 작업이 그대로 사라진다.
어떤 task가 풍부화되지 않았는지 알 방법도 없어 복구 진입점이 없다. Kubernetes 롤링 배포가
일상인 환경에서 배포할 때마다 일부 task가 조용히 fallback draft로 남는다. **채택하지 않는다.**

**(b) `@TransactionalEventListener(AFTER_COMMIT)` + Modulith event publication registry** —
커밋 시점 보장은 정확하고, `event_publication` 테이블이 미완료 event를 남겨 재시작 복구
진입점이 생긴다. 다만 이 레포는 아직 application event를 도입하지 않았고
(`docs/rules/persistence.md`가 "첫 application event 도입 시 `event_publication`을 Flyway로
추가"로 남겨둔 상태), 재발행 스케줄러와 운영 규약을 새로 만들어야 한다.

**(c) outbox 소비** — convert 트랜잭션이 이미 쓰는 `signal.converted_to_task`를 `minsu`가
소비해 원장을 materialize한다. `notification`의 watermark·polling 패턴을 그대로 쓸 수 있다.

**(d) convert 트랜잭션에서 원장을 직접 적재** — convert가 `tasks`·`signal_actions`·outbox와
같은 트랜잭션에서 `minsu` 생성 원장을 `pending`으로 함께 쓴다. 커밋 후에는 scheduler가 원장을
직접 claim한다. 원장 자체가 durable queue다.

### 5.2 (c)를 채택하지 않은 이유

(c)는 `notification` 선례를 그대로 쓸 수 있어 처음 후보였지만, 실제 계약을 확인하니 세 가지가
막힌다.

**task_id를 얻을 수 없다.** `SignalActionExecutor.convert`는 `aggregateId`에 Signal ID를 넣고
task_id는 payload에만 담는데, 공개 `OutboxEventView`에는 payload 필드가 없다("지금의 유일한
소비자가 payload를 쓰지 않으므로 담지 않는다"). consumer가 어떤 task를 처리할지 식별할 수
없다.

**최초 watermark 시드가 작업을 버린다.** `SignalCreatedDeliveryMaterializer`는 offset이 없으면
`latestIdBefore(safetyLag)`로 시드한다. 과거 알림을 소급 발송하지 않으려는 의도적 정책인데,
생성 작업에 그대로 쓰면 시드 이전에 커밋된 convert가 영구히 누락된다. 알림은 놓쳐도 되지만
생성 작업은 아니다.

**커밋과 materialize 사이에 상태가 없다.** convert 응답 시점에는 outbox row만 있고 원장 행이
없다. 7.1절 상태표를 적용할 대상이 없어, 응답의 `draft_status`를 feature flag만 보고 정해야
한다. 그러면 rollout·rollback 뒤 과거 task의 상태 해석이 달라진다.

이 셋은 각각 outbox 공개 계약 확장, 별도 cutover 규칙, durable intent 마커로 개별 대응할 수
있지만, (d)는 셋을 한꺼번에 없앤다.

### 5.3 채택: (d) convert 트랜잭션 적재

**convert 트랜잭션이 `minsu` 생성 원장을 `pending`으로 함께 쓰고, `minsu` scheduler가 원장을
직접 claim한다.**

`signal`은 이미 `minsu`에 의존하고
(`SignalActionServiceImpl.generateDraft`가 `SignalTaskDraftInput`을 직접 조립한다), 그 입력을
그대로 `minsu`의 적재 API에 넘기면 된다. 적재 방향은 오늘과 같은 `signal → minsu`다.

- task_id는 적재 시점에 이미 있다.
- watermark·시드·cutover가 필요 없다.
- 원장 행이 응답 커밋 전에 존재하므로 `draft_status`를 원장에서 그대로 읽는다.
- 비동기 대상 여부가 원장 행의 존재로 durable하게 남는다. feature flag 해석에 의존하지 않아
  rollout·rollback 뒤에도 과거 task의 의미가 바뀌지 않는다.
- `pending` 원장 자체가 프로세스 재시작 복구 진입점이다.

원장 적재는 convert 트랜잭션의 일부이므로, 롤백되면 원장도 함께 사라진다. 커밋 전에 생성이
시작되는 일은 없다. scheduler는 커밋된 `pending` 행만 본다.

**이 선택은 fail-closed다.** 원장 insert가 실패하면 convert 전체가 롤백되어 public API가
실패한다. 기존 동기 계약은 LLM 실패를 fallback으로 흡수해 convert 가용성을 지켰지만, 비동기
활성 후에는 원장 테이블·제약·lock 문제가 convert 실패로 이어질 수 있다.

| 정책 | 얻는 것 | 잃는 것 |
| --- | --- | --- |
| fail-closed (채택) | 모든 비동기 intent가 durable. 상태 공백 없음 | 원장 장애가 convert API 장애가 됨 |
| fail-open | convert 가용성 유지 | 그 task는 풍부화되지 않고 원장도 없어 조용히 `ready` |

실제로 fail-closed가 **추가하는** 위험은 표가 시사하는 것보다 작다. 원장 insert 실패의 대부분을
차지할 DB 장애·커넥션 고갈은 같은 트랜잭션의 `tasks` insert도 함께 실패시키므로 convert는 어차피
실패한다. 순수하게 늘어나는 위험은 원장 고유의 제약 위반과 락 경합뿐이고, `task_id` UNIQUE는
상류의 `signal_actions UNIQUE(signal_id)`가 이미 막고 있어 실질적으로 거의 남지 않는다.

fail-closed를 택한 이유는 fail-open의 실패가 **보이지 않기** 때문이다. 원장이 없으면 `ready`로
응답하므로 앱도 사용자도 서버도 무엇이 빠졌는지 알 수 없고, 원장 장애가 길어지면 그동안의
task 전부가 조용히 풍부화되지 않는다. convert 실패는 최소한 드러난다.

실패 경로를 구분해 둔다. 원장 insert 실패는 전체가 롤백되므로 `signal_actions`도 남지 않고,
다음 요청은 **신규 convert**로 처음부터 다시 처리된다. replay가 되는 것은 커밋은 됐는데 응답이
유실된 경우다. 두 경우를 섞으면 장애 분석이 어긋난다.

대신 원장 insert 실패율을 지표와 경보로 두고(9.3절), 구현 티켓의 검증 기준에 포함한다.

기존 outbox event(`signal.converted_to_task`, `task.created`)는 그대로 둔다. 다만 **반영 시점에
후속 event를 하나 추가해야 한다**(5.4절).

### 5.4 반영 결과를 projection에 전달

convert는 fallback 값으로 task를 만들면서 `task.created`를 발행한다. worker는 이 event를 소비해
공유 DB에서 최신 상태를 hydrate하고 retrieval projection을 만든다(ADR-0008). 그런데 AI 결과는
그 뒤 별도 트랜잭션에서 `tasks`에 반영된다.

```text
1. fallback task + task.created 커밋
2. worker가 task.created 소비 → fallback title로 projection 생성
3. minsu가 AI draft를 tasks에 반영
4. 후속 event 없음 → projection은 fallback title로 영구 잔류
```

outbox consumer의 safety lag는 근거가 되지 못한다. LLM 호출과 재시도는 초 단위이고 queue
delay까지 더하면 safety lag를 쉽게 넘는다. 오히려 정상적인 경우 worker가 **먼저** 소비한다.

검색 read-model이 영원히 fallback title을 들고 있는 것은 사용자에게 보이는 결함이다. 따라서
**반영 트랜잭션에서 후속 event를 함께 발행한다.**

```text
event_type    task.draft_generated
aggregate     task
aggregate_id  task_id
payload       {} (ID 중심 원칙, worker가 DB에서 hydrate)
```

`task.updated` 같은 범용 이름 대신 1회성 의미가 분명한 이름을 쓴다. ADR-0010의
`{aggregate}.{과거형 동사}` 규약을 따르고, 한 task당 최대 한 번만 발행되므로
`event_type + aggregate_id` 멱등키와 잘 맞는다.

이 event는 **생성이 성공해 `tasks`를 실제로 갱신했을 때만** 발행한다. `user_edited`·
`task_gone`·`retry_exhausted`처럼 `tasks`가 바뀌지 않은 종료에서는 projection도 바뀔 것이
없으므로 발행하지 않는다.

**성공했지만 값이 그대로인 경우도 여기에 포함된다.** `GENERATED_TITLE_FALLBACK`은 성공
outcome이면서 title을 convert 시점 고정 fallback과 같은 규칙(`normalize(signal.title)`)으로
만들기 때문에, role·priority까지 `pm`·`medium`이면 생성 결과가 baseline과 정확히 일치한다.
반영은 `applied`로 돌아오지만 dirty check가 UPDATE를 내지 않아 `tasks`는 그대로다. 원장은
`generated`로 닫되 발행은 건너뛴다. 판단은 baseline을 소유한 `minsu`가 하고, `project`의 반영
결과 enum에 "값이 그대로다"를 추가하지 않는다(8.1절이 `USER_EDITED`를 두지 않은 것과 같은 이유).

발행은 8.3절의 반영 트랜잭션에 합류한다. 따라서 `minsu → outbox` 의존이 하나 더 생긴다.
`OutboxAppender`는 호출자 트랜잭션 안에서 호출해야 하므로(트랜잭션 없이 호출하면 실패한다)
이 배치가 계약과 맞는다.

worker 쪽 구현(새 event_type 소비와 재-hydrate)은 `momens-worker`의 범위다. 다만 **배포 순서는
이 설계의 전제 조건이다.**

watermark 기반 consumer는 모르는 event를 읽어도 offset을 전진시킨다. 그러면 producer를 먼저
배포한 구간의 `task.draft_generated`는 새 worker가 배포된 뒤에도 다시 읽히지 않고, 그 사이
생성된 task의 projection이 fallback title로 영구 잔류한다. 앞서 "worker가 무시하면 된다"고
넘길 수 있는 문제가 아니다.

따라서 다음 중 하나를 만족해야 비동기 생성을 활성화한다.

- **worker-first 배포** — worker가 이 event를 소비할 수 있게 된 뒤에 서버의 비동기 생성을
  켠다. 가장 단순하고 이 방식을 기본으로 한다.
- 또는 worker가 모르는 event_type에서 watermark를 전진시키지 않음이 구현·검증됐거나, cutover
  watermark·backfill 절차가 정의돼 있다.

11절의 prod 활성화 조건에 **worker 소비 배포와 end-to-end projection 검증**을 포함한다.

ADR-0008과 ADR-0010의 MVP event 목록에 이 event를 추가하는 것은 ADR-0015가 함께 다룬다.

worker가 `task.created`를 원장 terminal까지 대기·재시도하게 하는 대안은 채택하지 않았다.
worker가 `minsu` 원장 스키마를 알아야 해서 결합이 훨씬 크다.

`notification`의 lease·claim token·백오프·at-least-once 패턴은 여전히 그대로 따른다. 달라지는
것은 작업이 원장에 들어오는 경로뿐이다.

| 요소 | notification | minsu(이 설계) |
| --- | --- | --- |
| 작업 유입 | outbox 소비 후 materialize | convert 트랜잭션이 직접 적재 |
| watermark | `NotificationConsumerOffset` | 필요 없음 |
| 작업 원장 | `push_deliveries` | `minsu` 생성 원장 |
| claim | `claim_token` + processing lease 30s | 동일 (8.4절) |
| 재시도 | 백오프 1s / 5s / 30s, `MAX_ATTEMPTS` | 동일 형태, 값은 구현 티켓 |
| 보장 | at-least-once | at-least-once |

### 5.5 공개 계약 변경: 동기 호출을 요청 경로에서 제거

**비동기가 활성이면 convert 요청 경로에서 LLM을 호출하지 않는다.** 3절이 지적한 지연 문제가
해결되는 근거가 이것이고, 이 설계의 목적 자체다.

그런데 현재 공개 계약 `SignalTaskDraftGenerator.generate`는 이를 표현할 수 없다.
`DefaultSignalTaskDraftGenerator.generate`는 비활성·설정 무효·입력 부족이 아닌 한 **항상**
provider를 호출한다. 비동기 활성 상태에서 고정 fallback을 얻으려고 이 메서드를 그대로 부르면
8초 예산이 응답 경로에 그대로 남는다.

따라서 `minsu` 공개 계약에 두 진입점을 둔다. 활성 여부 판정은 `minsu`가 소유하고 `signal`은
두 번 호출하기만 한다.

| 진입점 | 호출자 | 비동기 활성 | 비동기 비활성 |
| --- | --- | --- | --- |
| draft 확보 | `signal`, 쓰기 트랜잭션 **밖** | LLM 미호출, 고정 fallback 반환 | 현재와 동일하게 LLM 호출 |
| 원장 적재 | `signal`, 쓰기 트랜잭션 **안** | `pending` 행 적재 | 아무것도 하지 않음 |
| 상태 조회 | `signal`(replay), `mobile`(task 상세) | 원장에서 `draft_status` 판정 | 항상 `ready` |

세 번째 진입점을 계약으로 못 박는 이유는 **deadline 투영(8.6절)을 내장해야 하는 유일한
지점**이기 때문이다. `signal`과 `mobile`이 각자 투영 로직을 구현하면 반드시 어긋난다.
`draft_status` 판정은 `minsu`가 한 곳에서 소유한다.

두 진입점을 나누는 이유는 트랜잭션 경계가 다르기 때문이다. 동기 모드의 LLM 호출은 지금처럼
쓰기 트랜잭션 밖에 있어야 하고(느린 네트워크가 DB connection을 점유하지 않도록), 원장 적재는
`tasks`·`signal_actions`와 원자적이어야 하므로 트랜잭션 안이어야 한다. 하나로 합치면 둘 중
하나를 포기하게 된다.

원장 적재 진입점은 `MANDATORY` 전파로 활성 트랜잭션을 요구한다(MOM-0818에서 확정, `OutboxAppender`와
같은 방식). 트랜잭션 밖에서 부르면 원장 insert가 자기 트랜잭션을 열어 커밋하므로 `tasks`가 롤백돼도
원장만 남는데, 그 행은 **적재에 성공했으므로 9.3절의 실패율 지표에도 잡히지 않는다.** 동기 모드의
no-op 경로에도 같은 요구를 걸어, 계약 위반이 비동기를 켠 환경에서 처음 드러나지 않게 한다.

기존 `generate` 시그니처는 **제거하고 draft 확보 진입점이 흡수한다**(MOM-0818에서 확정).
비동기 비활성일 때 `prepare`의 동작이 기존 `generate`와 완전히 같아 기능적으로 진부분집합이고,
남겨 두면 판정 1회 불변식을 우회하는 호출구가 계약에 남는다. 소비자는 `SignalActionServiceImpl`
한 곳뿐이라 흡수 비용도 작다. `signal`은 활성 여부를 알지 못하고 `minsu`가 판정을 소유한다는 점은
변하지 않는다. 9.2절의 "`disabled`면 적재하지 않는다"도 같은 원칙이다.

진입점은 계약을 둘로 나눠 갖는다. 적재 계열(`prepare`·`enroll`)은 `signal`만 부르고, 상태 조회는
`signal`과 `mobile`이 함께 부르므로 별도 인터페이스다.

**활성 판정은 한 요청 안에서 한 번만 하고 두 진입점이 같은 값을 쓴다.** 판정이 갈리면 비활성으로
LLM을 부르고 활성으로 적재해 중복 생성과 baseline 불일치가 생기거나, 활성으로 fallback만 확보하고
비활성으로 적재하지 않아 **LLM을 한 번도 부르지 않은 채 `ready`** 가 된다. 후자는 조용한 품질
손실이다.

이 불변식은 계약 형태로 강제한다. draft 확보가 **불투명한 준비 결과**를 반환하고 적재는 그것을
그대로 받는다.

```text
PreparedTaskDraft   // 공개 계약. draft()만 노출한다
  ├ 동기 준비 결과   // minsu 내부 타입. 적재할 것이 없다
  └ 적재 준비 결과   // minsu 내부 타입. draft와 입력 snapshot을 봉인한다
```

활성 여부를 **필드가 아니라 구현 타입에** 담는다(MOM-0818에서 확정). 필드로 두면 호출자가 값을
읽지 못해도 존재 여부는 보이고, 그 순간 `isPresent()` 분기가 가능해져 판정 소유권이 관례로
내려앉는다. 구현이 `minsu` 내부에만 있으면 호출자는 읽을 수도, 물을 수도, 직접 만들 수도 없다.

`signal`은 boolean으로 분기하지 않고 준비 결과를 트랜잭션 안의 적재 API에 전달하기만 한다.
판정 소유권, 판정 1회, 서로 다른 두 트랜잭션 경계가 모두 유지된다. 판정을 나중에 동적 토글로
바꿔도 요청 내 일관성이 계약으로 남는다.

### 5.6 생성 입력의 시점

원장에 **convert 시점의 `SignalTaskDraftInput` snapshot을 저장한다.**

실행 시점에 Signal을 다시 읽는 방식(hydrate)은 `minsu → signal` 의존을 새로 만들고, Signal이
그 사이 수정·삭제되면 사용자가 convert를 누른 시점과 다른 근거로 draft가 만들어진다. 동기
경로는 convert 시점 입력을 쓰므로 snapshot이 현재 의미를 그대로 보존한다.

대가는 Signal의 description·impact·evidence 텍스트가 원장에 복제된다는 점이다. 같은 DB·같은
workspace 데이터 안의 복제다.

크기는 evidence가 아니라 **description과 impact가 좌우한다.** evidence는 이미 10건·필드당
30자 상한이 걸려 있어 약 900자로 묶이지만, `signals.description`은 `TEXT NOT NULL`,
`impact`는 `TEXT`로 스키마상 상한이 없다(`V20260707100000__create_signals.sql`). worker가
생산하는 요약이라 실제 값은 짧을 것으로 보지만 설계가 기대는 근거는 아니다.

다음을 구현 티켓에서 정한다.

- 원장 적재 시 description·impact에 자체 상한을 둘지, 둔다면 그 값
- 원장이 terminal 상태로 닫힌 뒤 snapshot을 비우는 보존 정책
- 원문·URL·작성자를 담지 않는다는 동기 설계 6절의 입력 제외 목록이 원장에도 그대로 적용됨

## 6. 생성 원장 소유권

**`minsu`가 별도 생성 원장 테이블을 소유하고, `tasks`에는 확정된 결과만 반영한다.**

`tasks`에 상태 컬럼을 얹는 안은 채택하지 않았다. `tasks`는 사용자가 `TaskWriter.update`로
편집하는 테이블이라 AI 갱신과 사용자 수정이 같은 row에서 경합하고, 원장을 `tasks`에 두면
생성 상태의 소유가 `project`로 넘어가 모듈 경계가 흐려진다.

원장은 `notification`의 `push_deliveries`와 같은 성격이다.

| 항목 | 내용 |
| --- | --- |
| 소유 모듈 | `minsu` |
| 적재 시점 | convert 트랜잭션 (5.3절) |
| 멱등 키 | `task_id` UNIQUE. convert 1건당 task 1건이 이미 `signal_actions UNIQUE(signal_id)`로 보장된다 |
| 보유 데이터 | `task_id`, `workspace_id`, 입력 snapshot(5.6절), **반영 baseline**, **`read_deadline_at`·`apply_cutoff_at`**, 상태, completion reason, 시도 횟수, claim token, lease, `next_attempt_at` |
| 상태 | `pending` → `processing`(claim) → `completed`(reason 별도, 8.3절) |
| 재시도 | 시도 횟수와 다음 시도 시각을 원장이 소유 |
| 유실 복구 | lease 만료분과 재시작 후 남은 `pending`을 매 주기 회수 |

**baseline은 원장이 값으로 소유한다.** convert가 `tasks`에 실제로 쓴 title/role/priority를
적재 시점에 그대로 복사해 둔다. 반영 시점에 고정 fallback 규칙을 다시 계산해 비교하지 않는다.
재계산 방식은 fallback 생성 규칙(`TaskTitleNormalizer` 등)이 바뀌는 순간 진행 중이던 작업
전부가 CAS 불일치가 되어 편집이 없는데도 `user_edited`로 오분류된다. 8.1절이 내세우는
"오탐 0"이 규칙 변경 한 번에 무너지는 것이다. baseline을 값으로 저장하면 규칙이 바뀌어도 과거
작업이 안전하고, snapshot 보존 정책(5.6절)과도 분리된다. snapshot은 종료 후 비워도 baseline은
남는다.

`workspace_id`를 갖는 이유는 원장이 Signal 본문을 복제해 실질적으로 workspace 데이터이기
때문이다. `signal_actions`와 `outbox_events`도 모두 갖고 있고, 운영 쿼리·지표를 workspace로
분해하려면 필요하다. prod 데이터 레지던시 판단이 workspace 단위라 정책 적용 대상을 고르는
데도 쓰인다.

`tasks`에는 생성이 성공한 시점에만 title/role/priority를 반영한다. 어떤 이유로 끝나든
`tasks`는 convert 시점의 고정 fallback draft를 최소한으로 갖는다. 따라서 **`tasks`는 항상
유효한 draft를 갖는다**는 현재 성질이 비동기 전환 후에도 보존된다.

세부 컬럼과 Flyway 파일은 구현 티켓에서 확정한다. prod 반영은 공유 운영 스키마를 레거시가
단일 소유하므로(`docs/rules/persistence.md`) `momens-api` 마이그레이션 PR로 추가한다.

### 6.1 실행 흐름과 모듈 경계

```text
convert-to-task (동기)
  → SignalReader로 Signal·evidence 조회, SignalTaskDraftInput 조립  (signal, 현재와 동일)
  → minsu에서 draft 확보 (활성이면 LLM 미호출 고정 fallback, 5.5절)  (signal → minsu)
  → SignalActionExecutor.convert 트랜잭션
      tasks insert
      signal_actions insert
      minsu 생성 원장 insert (pending, 입력 snapshot 포함)          (signal → minsu)
      outbox_events insert (signal.converted_to_task, task.created)
  → commit → draft_status를 원장에서 읽어 응답

minsu scheduler (비동기)
  → pending 또는 lease 만료 원장 claim (claim token + lease commit)
  → 트랜잭션 밖에서 LlmClient.generate
  → 성공: 한 트랜잭션에 tasks CAS 반영 + 원장 completed + task.draft_generated
          (claim token·deadline 조건)                          (minsu → project, minsu → outbox)
  → retryable 실패: 백오프 후 재시도 예약
  → terminal 실패: 원장 completed + reason (tasks는 고정 fallback 유지)

task 상세 조회
  → project에서 task 조회 + minsu에서 draft_status 조회               (mobile → minsu)
```

의존은 넷이고 그중 **셋이 새로 생긴다.**

| 의존 | 용도 | 상태 |
| --- | --- | --- |
| `signal → minsu` | 원장 적재, draft 확보, 상태 조회 | 오늘 이미 존재 |
| `minsu → project` | 생성 결과를 `tasks`에 반영 | **신규** |
| `minsu → outbox` | 반영 시 `task.draft_generated` 발행 (5.4절) | **신규** |
| `mobile → minsu` | task 상세의 `draft_status` 읽기 | **신규** |

`mobile → minsu`가 필요한 이유는 7.2절이 task 상세 조회에도 `draft_status`를 노출하기
때문이다. task 상세 경로는 `TaskController` → `ProjectTaskService` → `project`이고
`modules/mobile/build.gradle`에는 현재 `minsu`가 없다. convert 응답 경로는 `signal`이 원장을
읽어 `SignalActionResult`에 실으면 되므로 기존 의존으로 충분하지만, task 상세는 그렇지 않다.

`project → minsu`로 해결하려는 시도는 **금지한다.** `minsu → project`와 순환이 되어
`ApplicationModules.verify()`가 실패한다. 상태를 읽는 쪽은 조합 표면인 `mobile`이어야 한다.

`minsu`가 `signal`·`mobile`에 의존하지 않는다는 규칙은 그대로 유지된다. `mobile → minsu`는
반대 방향이라 이 규칙과 무관하고, 입력을 convert 시점 snapshot으로 받으므로(5.6절) `signal`을
참조할 이유도 없다.

`project`와 `minsu`는 서로를 모르는 관계에서 `minsu → project` 단방향으로 바뀐다. 이 제약은
동기 설계 4절("`minsu`는 `signal`, `project`, `mobile`에 의존하지 않는다")과 ADR-0014가 함께
전제한 것으로, 첫 슬라이스를 작게 유지하기 위한 조건이었고 비동기 전환으로 전제가 바뀌었다.
이 변경은 ADR-0014를 supersede 하는 [ADR-0015](../adr/0015-minsu-async-task-draft-generation.md)가
확정하며, `minsu → project` 참조 범위는 draft 반영 한 가지로 제한한다.

`signal`이 비동기 실행까지 조립하는 안은 채택하지 않았다. 생성 원장 소유가 `signal`로 넘어가
6절 결정과 어긋나고, Signal action의 멱등·충돌 정책을 담당하는 모듈이 LLM 작업의 claim·
재시도·백오프 운영까지 떠안게 된다. `signal`이 하는 일은 오늘처럼 입력을 조립해 `minsu`에
넘기는 것까지다.

## 7. 상태 전이와 모바일 계약

### 7.1 상태 전이

원장 상태와 API가 노출하는 상태를 분리한다. 상태는 셋이고, 종료 사유는 상태가 아니라
`completion_reason`으로 따로 갖는다.

| 원장 상태 | 의미 | API 노출 |
| --- | --- | --- |
| `pending` | 적재됨 또는 재시도 대기. 생성 대기 | `generating` |
| `processing` | claim 보유, 생성 중 | `generating` |
| `completed` | 종료. 사유는 `completion_reason` | `ready` |

전이는 다음으로 고정한다. 모든 전이는 claim token 조건으로 갱신한다.

```text
pending(next_attempt_at ≤ now)
  → processing(new token, lease, attempt+1)      claim

processing
  → completed                                    성공 또는 terminal 실패
  → pending(next_attempt_at, token/lease 비움)    retryable 실패

processing(lease 만료)
  → processing(new token, lease, attempt+1)      장애 복구 재claim
```

- **retryable 실패는 `pending`으로 되돌린다.** `processing`을 유지한 채 token만 비우면
  "claim 보유 중"이라는 상태 의미와 어긋난다. 되돌릴 때 이전 token과 lease를 함께 정리한다.
- **시도 횟수는 claim 시점에 증가한다.** 결과 기록 시점에 올리면 claim 후 프로세스가 죽은
  시도가 세지 않아 무한 재시도가 된다.
- **`lease`와 `next_attempt_at`은 별도 컬럼이다.** 전자는 실행 중 소유권 만료, 후자는 대기 중
  재시도 시각으로 의미가 다르다. 한 컬럼으로 겸하면 lease 만료 회수와 백오프 대기를 구분할 수
  없다.

| `completion_reason` | 의미 | `tasks` 반영 |
| --- | --- | --- |
| `generated` | 모델 draft 반영 | 반영됨 |
| `user_edited` | 사용자가 먼저 편집해 결과 폐기 (8.1절) | 사용자 편집 유지 |
| `task_gone` | 대상 task가 삭제됨 (8.1절) | 대상 없음 |
| `operationally_closed` | 운영 판단으로 수동 종료 (11.1절) | 고정 fallback 유지 |
| `deadline_exceeded` | 원장 나이 상한 초과 (8.6절) | 고정 fallback 유지 |
| `insufficient_context` | 입력 부족으로 재시도 없이 종료 | 고정 fallback 유지 |
| `invalid_config` | 설정 무효로 종료 | 고정 fallback 유지 |
| `retry_exhausted` | 재시도 상한 도달 | 고정 fallback 유지 |

종료 사유를 상태로 만들지 않은 이유는 `user_edited`가 성공도 실패도 아니기 때문이다. 모델은
정상 생성했지만 반영하지 않았으므로 `succeeded`가 아니고, 재시도 상한에 도달한 실패도 아니다.
상태를 늘려 표현하면 "종료됐는가"를 판정할 때마다 상태 목록을 열거해야 한다. 상태는 진행
여부만 표현하고 사유는 별도 컬럼으로 두는 편이 API 매핑과 운영 판단 모두 단순하다.

**API는 `generating`과 `ready` 두 값만 노출한다.** 모든 `completed`는 사유와 무관하게 `ready`로
매핑한다. 동기 설계 5절의 "fallback 여부와 이유는 public API 응답에 넣지 않고 내부 관측으로
남긴다"를 그대로 유지하기 위해서다. 앱에게 필요한 정보는 "이 title이 앞으로 더 바뀌는가"
하나이고, 그 점에서 모든 종료 사유는 같다. 사유는 원장과 관측에만 남는다.

더 결정적인 이유는 **앱이 실패를 알아도 할 수 있는 것이 없다**는 점이다. 재생성 API가 없으므로
"생성 실패"를 구분해봐야 앱이 취할 행동은 "fallback title을 그대로 표시" 하나뿐이고 그것은
`ready`와 같다. 재생성 API가 생기면 그때 사유 노출을 함께 판단한다.

원장 행이 없는 task(비동기 도입 이전에 만들어졌거나 비동기 비활성 상태에서 convert된 task,
그리고 Signal을 거치지 않은 일반 task)는 `ready`로 응답한다. 원장 행의 존재 자체가 비동기
대상 여부를 durable하게 표현하므로, feature flag의 현재 값으로 과거 task의 상태를 해석하지
않는다. rollout·rollback 뒤에도 판정이 달라지지 않는다.

### 7.2 모바일 계약 변경

`ConvertToTaskResponse`에 draft 생성 상태를 추가한다. unknown field를 허용하는 클라이언트에는
하위 호환 확장이다. `momens-android`의 현재 decoder는 `ignoreUnknownKeys = true`이므로 필드
추가만으로 기존 앱의 응답 파싱이 깨지지 않는다. 이 사실은 확인했고, 앱 배포 순서는 계약이 아니라
효과의 문제로 남는다(11절 prod 활성화 조건).

```text
{
  "task":   { "id", "title", "status", "draft_status": "generating" | "ready" },
  "signal": { "id", "action" }
}
```

- 원장 행이 없으면(비동기 비활성 포함) 항상 `ready`로 응답한다. 동기 경로와 계약이 갈라지지
  않는다.
- 원장 행이 convert 트랜잭션에서 함께 커밋되므로 신규 convert 응답과 직후 재조회 모두 원장을
  읽어 상태를 정한다. 응답 시점에 행이 없는 구간이 존재하지 않는다.
- 멱등 replay 응답도 같은 규칙을 따른다. 이미 생성이 끝났으면 `ready`, 아직 진행 중이면
  `generating`이다.
- 앱은 `generating`일 때 로딩 표시를 할지, fallback title을 그대로 보여주고 조용히 갱신할지
  스스로 정한다. 서버는 사실만 알리고 UX 판단을 앱에 남긴다.
- task 상세 조회 응답에도 같은 값을 노출해 앱이 재조회로 종료를 확인할 수 있게 한다.
- push 통지는 1차 범위에서 제외한다. push는 토큰 무효·알림 권한 거부·앱 종료로 수신 보장이
  약해 재조회 경로를 대체하지 못하고 그 위에 얹는 최적화이므로, 재조회가 자리 잡은 뒤 별도로
  판단한다.
- **결과를 확인하는 경로는 convert 응답과 task 상세 둘뿐이다.** task 목록·보드에는
  `draft_status`를 노출하지 않는다. 보드 카드의 title은 사용자가 상세에 들어가지 않으면
  fallback인 채로 있다가 다음 목록 조회에서 조용히 바뀐다. 7.2절의 "`generating` 동안에도
  title은 항상 유효한 draft"가 성립하므로 계약 위반은 아니지만, 앱이 보드 캐시 무효화를
  판단하려면 이 사실을 알아야 하므로 모바일 통보에 포함한다.

이 계약은 서버가 확정하고 모바일에 알린다. 앱이 `generating`을 어떻게 표시할지, 재조회를
언제 어떤 간격으로 할지는 앱이 정한다.

한 가지는 알리면서 함께 전달한다. task 상세 조회는 담당자·materials 등을 함께 hydrate하는
무거운 경로라 짧은 간격의 polling에 적합하지 않다. 앱이 폴링 부담을 문제 삼으면 경량 상태 조회
엔드포인트나 `retry_after` 계열 힌트를 후속으로 검토한다. 지금 미리 만들지는 않는다. 서버가 보장하는 것은 다음 셋이다.

- `draft_status`는 반드시 종료 상태(`ready`)에 도달한다(8.6절).
- **정상 종료에서는** `ready`와 함께 반환한 `task.title`이 최종 값이다. `ready`를 보고 재조회를
  멈춰도 갱신을 놓치지 않는다(7.3절). 예외는 deadline 초과로 닫힌 경우 하나이며 그 창은
  8.6절에 적는다.
- `generating` 동안에도 `task.title`은 항상 유효한 draft다. 앱이 로딩을 표시하지 않고 그대로
  렌더해도 깨지지 않는다.

### 7.3 응답 조립의 읽기 순서

`tasks`와 원장은 서로 다른 모듈이 각각 읽는다. PostgreSQL 기본 격리 수준
`READ COMMITTED`에서는 두 SELECT가 다른 snapshot을 볼 수 있어, 순서를 정하지 않으면 다음이
가능하다.

```text
1. task를 읽는다 → fallback title
2. scheduler가 AI title + completed 를 원자적으로 커밋
3. 원장을 읽는다 → ready
4. 앱은 fallback title + ready 를 받고 재조회를 멈춘다
```

서버는 AI 결과를 정상 저장했는데 앱은 fallback title을 영구히 표시한다. 8.3절의 쓰기 원자성은
이 문제를 막지 못한다. 쓰기가 원자적이어도 **읽기가 두 번**이기 때문이다.

따라서 읽기 순서를 계약으로 고정한다.

**신규 convert 응답** — 원장을 적재했으면 상태를 다시 읽지 않고 `generating`을 반환한다.
응답에 담는 title은 방금 만든 fallback이므로 이 조합은 항상 정합하다. 적재하지 않았으면
(비동기 비활성) `ready`다.

**replay 응답과 task 상세** — **원장 상태를 먼저 읽고 그다음 task를 읽는다.**

- `ready`를 먼저 봤다면 그 뒤 읽는 task는 반영이 끝난 상태이거나 그보다 더 최신이다. 따라서
  `ready`와 함께 반환하는 title은 최종 값이다.
- `generating`을 본 뒤 scheduler가 끝나면 AI title + `generating`이 될 수 있다. 이 조합은
  안전하다. 앱이 한 번 더 재조회할 뿐 결과를 놓치지 않는다.

역순(`task` → 원장)은 위 시나리오를 그대로 허용하므로 금지한다.

**이 순서 계약은 PostgreSQL 기본 격리 수준 `READ COMMITTED`를 전제한다.** 두 SELECT가 각각
그 시점의 snapshot을 뜨기 때문에 "나중에 읽은 값이 더 최신"이 성립한다. 응답 조립이 한
트랜잭션 안에서 일어나도 무방하지만, 격리 수준을 `REPEATABLE READ` 이상으로 올리면 첫 문장이
snapshot을 고정하므로 **순서만으로는 정합성이 보장되지 않는다.**

이 전제가 깨지기 쉬운 자리가 실제로 있다. `ProjectTaskService.getTaskDetail`은
`@Transactional(readOnly = true)`이고 여기에 원장 조회가 추가된다. 지금은 성립하지만 격리
수준을 올리거나 두 값을 한 번에 뜨는 최적화가 들어가면 계약이 조용히 무너진다.

구현 티켓에는 scheduler 완료가 응답 조립 사이에 끼어도 `fallback title + ready`가 나오지 않는
동시성 테스트를, **같은 트랜잭션·`READ COMMITTED` 조건에서** 포함한다.

## 8. 경합·멱등성·실패 복구

### 8.1 사용자 수정과 AI 결과의 경합

**사용자 수정이 항상 우선한다.** convert 직후 사용자가 `TaskWriter.update`로 제목을 고쳤는데
뒤늦게 도착한 AI 결과가 이를 덮으면 사용자가 명시적으로 한 편집이 사라진다.

판정은 **조건부 UPDATE(compare-and-set)** 로 한다. 반영 시점에 `tasks`의 title/role/priority가
여전히 **원장에 기록된 baseline**(6절)과 같을 때만 갱신한다. 값이 달라졌으면 사용자가 이미
편집한 것이므로 AI 결과를 폐기하고 원장을 닫는다.

CAS 조건에는 `deleted_at IS NULL`도 포함한다. 이 서버에는 아직 task 삭제 API가 없지만
`tasks.deleted_at`이 존재하고 `TaskRepository`가 모두 이 조건으로 필터하며, prod는 레거시
`momens-api`와 DB를 공유하므로 삭제가 레거시 쪽에서 일어날 수 있다. 세 필드만 비교하면 삭제된
행에 AI 결과를 쓰고 `generated`로 집계된다. 이 경우는 `user_edited`가 아니라
`task_gone`으로 닫아 지표를 분리한다.

`updated_at` 비교는 쓰지 않는다. 체크리스트 토글처럼 draft와 무관한 갱신에도 걸려, 실제 편집이
없는데 결과를 버린다.

두 방식의 오차 방향은 다르다.

- 조건부 UPDATE는 **편집이 없는데 버리는 오탐이 없다.** 값이 fallback 그대로면 title·role·
  priority가 실질적으로 바뀌지 않은 것이 확실하다.
- 대신 **편집을 놓치는 미탐이 있다.** title/role/priority가 결과적으로 fallback과 같으면
  편집으로 감지되지 않아 AI 결과가 반영된다.

미탐은 드물지 않다. `UpdateTaskCommand`는 "수정 화면이 저장할 때 편집 상태 전체를 보내므로
title, role, priority, status, purpose는 항상 채워진 값으로 넘어온다". 즉 사용자가 purpose나
완료 기준만 고쳐 저장해도 title/role/priority는 fallback 값 그대로 다시 쓰이고, 그 뒤 도착한
AI 결과가 title을 덮는다.

그래도 미탐을 허용한다. 이 경우 사용자는 title을 건드리지 않았으므로 편집 의도가 사라지지
않고, 반영되는 AI 결과 역시 유효한 draft다. 반대로 오탐은 실제 편집을 덮거나 생성 결과를
이유 없이 버리므로 피해가 실질적이다. 오탐 0을 택했다.

**CAS는 세 필드를 함께 본다.** title/role/priority 중 하나라도 달라지면 **draft 전체를 폐기한다.** 필드별로 나눠 일치하는
것만 반영하지 않는다.

부분 반영은 사용자가 보는 draft를 사람과 모델의 혼합물로 만든다. 예를 들어 사용자가 priority만
높였는데 AI가 만든 title이 반영되면, 그 title은 사용자가 판단한 priority를 전제로 쓰인 것이
아니다. 동기 설계 9절이 "role 또는 priority가 무효면 title만 쓰지 않고 draft 전체를 고정
fallback으로 바꾼다"며 값을 섞지 않기로 한 것과 같은 원칙이다.

대가는 사용자가 priority 하나만 바꿔도 AI title까지 버려진다는 점이다. 이를 감수한다. 반대
방향의 혼합물이 사용자에게 설명하기 더 어렵다.

### 8.2 실행 fencing

claim은 lease 만료 시각만으로 충분하지 않다. 동기 경로에서 확인했듯 SDK timeout이 ADC 탐색·
client 생성·token refresh 전체를 덮지 않으므로(4절), lease보다 오래 걸리는 호출이 가능하다.

```text
worker A가 claim → 모델 호출
  → 호출이 길어져 lease 만료
  → worker B가 재claim → 호출
  → worker A가 뒤늦게 성공 반환
  → fencing이 없으면 A와 B가 모두 결과를 기록
```

`notification`의 `PushDelivery`가 `claim_token`을 저장하고 결과 기록 시 `isClaimedBy`로 소유권을
재검증하는 것과 같은 규칙을 correctness requirement로 둔다.

- lease 만료 판정은 DB 시계를 기준으로 한다. 앱 서버 간 시계 차이의 영향을 받지 않는다.
- claim마다 새 token을 발급하고 원장에 저장한다.
- 결과 기록·재시도 예약·terminal 전이는 **모두 claim token이 일치할 때만** 수행한다. token이
  다르면 그 결과는 stale이므로 버리고 관측에만 남긴다.
- 장시간 호출 중 lease 갱신 여부는 구현 티켓에서 정한다. 갱신하지 않으면 lease를 비동기
  timeout보다 충분히 크게 잡아야 한다.

### 8.3 반영의 원자성

**`tasks` CAS 반영과 원장 terminal 전이는 한 트랜잭션이어야 한다.** 나누면 다음이 가능하다.

```text
1. tasks CAS update commit
2. 프로세스 종료
3. 원장은 processing으로 남음
4. 재시도에서 CAS가 0건 (이미 모델 값으로 바뀌었으므로)
5. 사용자 편집으로 오판해 user_edited로 종료
```

결과 자체는 이미 반영됐으므로 사용자 피해는 없지만 종료 사유가 틀리고, 그 사유는 운영 판단에
쓰인다(9.2절). claim token 조건 아래 두 변경을 한 트랜잭션에서 커밋한다.

1. `tasks` 조건부 UPDATE
2. 원장 `completed` + `completion_reason` 기록

### 8.4 멱등성

- 원장 적재는 convert 트랜잭션의 일부라 정확히 한 번이다. 트랜잭션이 롤백되면 원장도 없다.
  재시도로 들어온 convert는 `signal_actions UNIQUE(signal_id)`에 걸려 replay가 되므로 원장을
  다시 적재하지 않는다. `task_id` UNIQUE가 이중 방어로 남는다.
- **결과 기록**은 claim token으로 직렬화한다. token을 보유한 실행만 기록할 수 있다.
  이는 exactly-once **기록**이지 exactly-once **실행**이 아니다. lease가 만료되면 8.2절처럼
  두 worker가 동시에 모델을 호출할 수 있고, at-least-once와 만료 lease를 택한 이상 복구
  과정의 중복 실행은 정상 동작이다. 중복 실행의 비용은 관측으로 본다(9.3절).
- 반영은 조건부 UPDATE라 두 번 적용해도 결과가 같다. 첫 번째가 값을 바꾸면 두 번째는 조건에
  걸려 아무것도 하지 않는다.

### 8.5 실패 복구

| 상황 | 복구 |
| --- | --- |
| convert 커밋 후 scheduler 실행 전 종료 | `pending` 원장이 남아 다음 주기가 집어간다 |
| claim 보유 중 프로세스 종료 | lease 만료 후 다음 주기가 회수해 재시도한다 |
| 모델 호출 실패·timeout | 백오프 후 재시도, 상한 도달 시 `retry_exhausted` |
| stale worker의 늦은 성공 | claim token 불일치로 기록이 거부된다 (8.2절) |
| 반영 트랜잭션 도중 종료 | 커밋되지 않아 원장이 `processing`으로 남고 lease 만료 후 재시도한다 |

어떤 경로로 끝나든 `tasks`는 convert 시점부터 **형식이 유효한** draft를 갖는다. 비동기 생성이
전부 실패해도 사용자가 보는 것은 현재 동기 경로의 fallback 결과와 같다. **비동기 전환은
가용성의 하한을 내리지 않는다.**

이것은 형식과 가용성의 하한이지 의미 품질의 하한이 아니다. schema 검증을 통과한 모델 결과가
Signal title보다 의미적으로 나쁠 수 있고 그때는 그 결과가 fallback을 덮는다. 이 위험은 동기
경로에도 똑같이 있으며 비동기 전환이 늘리지도 줄이지도 않는다.

### 8.6 `generating`의 상한

위 복구 경로는 모두 scheduler가 돌고 있다는 전제 위에 있다. scheduler 자체가 멈추거나
(11.1절) 회수 루프에 버그가 있으면 원장이 terminal로 갈 방법이 없고, 앱은 `generating`을
무기한 본다.

따라서 **원장 나이 상한(deadline)** 을 둔다. 적재 시각 기준으로 일정 시간이 지나면 그 작업은
종료된 것으로 본다. 상한은 재시도 백오프 총합보다 충분히 크게 잡는다. 기본값은 **1시간**이고
가드 밴드 margin은 **5분**이다(MOM-0818에서 확정, `momens.minsu.task-draft.async`). 상한이 짧아
정상 작업이 잘리면 품질 손실이 조용히 발생하지만 길어서 생기는 손해는 이미 경보 상태인 구간의
`generating` 노출뿐이라, 비대칭을 보고 넉넉한 쪽으로 잡았다. dev 재측정(MOM-0824) 뒤 조정한다.

중요한 것은 **이 상한을 누가 강제하는가**다. scheduler가 상한을 처리하게 두면 순환이 된다.
scheduler가 멈춘 상황을 막으려고 둔 장치를 그 scheduler가 실행하기 때문이다. 나이 상한은 행의
속성일 뿐 실행 주체가 아니다.

그래서 상한을 **읽기 시점에 투영한다.**

```text
draft_status =
  원장 행 없음                                       → ready
  completed                                          → ready
  pending/processing 이고 now() > read_deadline_at   → ready   (투영)
  그 외                                              → generating
```

`now()`는 원장과 같은 DB 시계다. 이 판정은 조회 경로에서 비교 하나로 끝나므로 scheduler가
멈춰 있어도, 설정을 꺼서 rollback해도 성립한다. 앱은 어떤 경우에도 `generating`에 무기한
갇히지 않는다.

deadline을 넘긴 뒤 뒤늦게 돌아온 scheduler가 결과를 반영하면 앱에 이미 `ready`로 알린 값이
바뀐다. 이를 막기 위해 **반영 CAS에도 deadline 조건을 넣는다.** deadline이 지난 작업은 claim
token이 맞아도 반영하지 않고 원장을 `deadline_exceeded`로 닫는다. 8.2절의 claim token fencing과
같은 성격의 조건이 하나 더 붙는 것이다.

두 조건이 같은 deadline을 쓰면 경계에서 어긋난다. 조건 평가와 커밋 사이에 읽기가 끼기
때문이다.

```text
T-ε  반영 트랜잭션이 deadline 조건을 평가 → 아직 안 지남, 통과
T    재조회: 원장 → now() > deadline → ready 투영, task → 아직 fallback title
T+ε  반영 트랜잭션 커밋 → tasks에 AI title
```

앱은 `ready` + fallback title을 받고 재조회를 멈췄는데 그 뒤 title이 바뀐다. 7.3절이 잡은
문제와 같은 형태가 deadline 축에서 한 번 더 나타난다.

따라서 **두 deadline에 가드 밴드를 둔다.**

```text
apply_cutoff_at   =  적재 시각 + 상한 - margin    (반영 CAS 조건)
read_deadline_at  =  적재 시각 + 상한             (읽기 투영 조건)
```

**두 값은 적재 시점에 계산해 원장에 저장한다.** 조회할 때마다 현재 설정으로 다시 계산하면
설정 변경이 과거 작업의 상태를 뒤집는다.

```text
상한 1시간으로 적재 → 90분 뒤 조회 → ready
배포로 상한을 3시간으로 변경 → 같은 원장 재조회 → generating
```

`ready`를 본 사용자가 다시 `generating`을 보게 되고, 그 뒤 뒤늦은 반영까지 들어오면 "`ready`
이후 갱신을 놓치지 않는다"는 계약이 깨진다. 상태는 단조로워야 한다. 이는 "feature flag의 현재
값으로 과거 task를 해석하지 않는다"(5.3절, 7.1절)와 같은 원칙이다.

상한과 margin 설정 변경은 **새로 적재되는 원장에만** 적용된다.

margin은 반영 트랜잭션이 조건 평가부터 커밋까지 걸리는 시간보다 충분히 크게 잡는다. 그러면 위
인터리빙은 반영 트랜잭션이 margin보다 오래 열려 있어야 성립한다. **margin이 0 이하이거나 상한
이상이면 부팅이 실패한다**(MOM-0818). 그대로 두면 `apply_cutoff_at < read_deadline_at` 제약에
걸려 적재가 전부 실패하는데, 적재는 convert 트랜잭션 안이라 그 실패가 사용자 요청 실패로 처음
드러난다.

**이것은 창을 좁히는 장치이지 불변식이 아니다.** PostgreSQL의 `now()`는 문장 평가 시각이 아니라
**트랜잭션 시작 시각**으로 고정되므로, cutoff 이전에 시작한 반영 트랜잭션이 lock 대기나 GC
pause로 지연되면 실제 projection deadline이 지난 뒤에도 조건이 참인 채 커밋할 수 있다.
`clock_timestamp()`로 바꿔도 조건 평가와 커밋 사이의 창 자체는 남는다.

따라서 7.2절의 title 최종성 보장은 **정상 종료에 한정**한다. deadline 초과로 닫힌 뒤 뒤늦은
반영이 커밋되면 `ready`로 알린 title이 한 번 바뀔 수 있다. 사용자에게는 "다음 상세 진입에서
제목이 달라 보임" 수준이고, deadline 발동 자체가 경보 대상인 이상 상황에서만 나타난다.

원장 row lock으로 deadline 종료와 성공 반영을 직렬화하면 절대 보장으로 올릴 수 있다. 채택하지
않았다. 조회 경로가 쓰기 lock을 잡아야 하고 lock 순서 규약이 추가되는데, 얻는 것은 이미 경보
상태인 경우의 title 한 번 변경을 막는 것뿐이다. 비용이 이득보다 크다.

원장의 물리적 정리(상태를 `completed`로 바꾸고 snapshot을 비우는 것)는 scheduler가 나중에
해도 된다. public 계약은 읽기 투영으로 이미 닫혀 있다.

이 보장의 범위는 **원장 행이 있는 task**다. `minsu` 자체가 없는 이전 바이너리로 rollback하면
응답에 `draft_status` 필드가 없으므로 앱은 이전 계약을 본다. 설정만 끄는 rollback과 바이너리
rollback의 차이는 11.1절에 정리한다.

상한이 발동한다는 것 자체가 이상 신호이므로 9.3절의 "가장 오래된 `pending`의 age",
`deadline_exceeded` counter와 함께 경보 대상으로 둔다.

## 9. timeout·retry·관측

### 9.1 비동기 경로의 timeout

동기 경로의 8초(MOM-0806)는 **그대로 둔다**. 이 값은 원탭 action의 사용자 체감을 기준으로
정했고, 비동기가 비활성일 때 convert가 타는 경로가 지금 그대로이기 때문이다. 비동기가
활성이면 요청 경로에서 LLM을 부르지 않으므로(5.5절) 이 값은 그 경로에 적용되지 않는다.

비동기 경로는 사용자가 기다리지 않으므로 별도 설정 키로 분리하고 더 큰 값을 갖는다. 구체적
값은 dev 재측정 뒤 확정한다. 동기 경로의 8초를 비동기 값으로 재사용하지 않는다. 두 경로가
같은 키를 공유하면 한쪽을 늘릴 때 다른 쪽의 사용자 체감이 함께 나빠진다.

재시도는 비동기 경로에서 **도입한다.** 동기 경로가 재시도를 두지 않은 이유는 사용자가 그
시간을 기다리기 때문이었고, 비동기에서는 그 제약이 없다. 백오프와 상한은 `push_deliveries`의
형태(고정 백오프 목록 + `MAX_ATTEMPTS`)를 따르되 값은 구현 티켓에서 정한다.

**SDK timeout만으로는 부족하다.** 4절에서 확인했듯 `HttpOptions.timeout`은 OkHttp `Call`
구간에만 걸리고 ADC 탐색·client 생성·token refresh는 그 밖이다. 동기 경로에서는 사용자 요청이
결국 끊기지만, 비동기에서는 아무도 기다리지 않으므로 전단에서 멈춘 호출이 그대로 남는다.

그 결과는 다음과 같다.

- lease가 만료될 때마다 다른 worker가 같은 작업을 다시 호출한다.
- 멈춘 호출은 취소되지 않고 scheduler 실행 슬롯을 계속 점유한다.
- 이런 작업이 쌓이면 scheduler 실행 용량이 고갈된다.
- deadline 투영으로 API는 `ready`가 되는데 실제 작업은 계속 누적된다.

따라서 **attempt 전체에 wall-clock 상한을 둔다.** SDK timeout이 아니라 ADC 탐색·client 생성·
token refresh·provider 호출을 모두 포함한 한 번의 시도 전체를 감싸는 상한이다. 상한을 넘으면
해당 시도를 포기하고 결과를 버린다. 실행 동시성도 bounded로 둔다.

**다만 이 상한이 호출을 실제로 취소하지는 못한다.** timeout wrapper가 하는 일은 호출자를 대기에서
풀고 결과를 버리는 것뿐이다. SDK나 ADC 호출이 interrupt에 응답하지 않으면 underlying thread는
계속 점유된다. `GoogleGenAiLlmClient`는 동기 `generateContent`를 호출하고 최초 client 생성을
`synchronized` 블록에서 수행하므로, 첫 생성이 멈추면 뒤따르는 호출도 같은 monitor에서 막힐 수
있다. bounded pool은 피해 규모를 슬롯 수로 제한할 뿐, 영구 정지 호출 N개가 N개 슬롯을 모두
소진하는 것을 막지 못한다.

**포화 가능성을 수용한다.** 취소 가능한 transport로 바꾸거나 attempt를 별도 프로세스로 격리하는
방안은 이 슬라이스의 범위를 크게 넘고, 얻는 것은 이미 이상 상황인 경우의 복구 시간 단축이다.
대신 포화를 **관측하고 복구할 수 있게** 한다.

- executor active count, queue depth, reject count
- attempt 상한을 넘겨도 반환하지 않은 hung attempt 수
- 포화가 지속되면 Pod 재시작으로 복구한다. 원장이 durable하므로 재시작 후 `pending`과 lease
  만료분을 그대로 이어서 처리한다. 유실은 없다.

구현 티켓의 검증에는 interrupt를 무시하는 fake client로 모든 슬롯을 점유한 뒤, 재시작 후 작업이
정상 복구되는지 확인하는 테스트를 포함한다.

네 시간 값의 선후 관계를 고정한다.

```text
attempt 상한  <  lease  <  apply_cutoff_at  <  read_deadline_at
```

attempt 상한이 lease보다 짧아야 정상 실행 중에 lease가 만료되어 중복 호출이 생기는 일이 없다.
`apply_cutoff_at`과 `read_deadline_at`의 관계는 8.6절의 가드 밴드다. 구체적 값은 dev 재측정 뒤
구현 티켓에서 정하되 이 부등식은 유지한다.

### 9.2 재시도 판정과 내부 실행 계약

공개 `SignalTaskDraftGenerator.generate`는 실패를 전파하지 않고 항상 유효한 `TaskDraft`를
반환한다. 반환값만 보면 그 draft가 모델 결과인지, 재시도하면 될 일시 실패인지, 재시도해도
같을 확정 fallback인지 구분할 수 없다. **이 계약만으로는 재시도를 구현할 수 없다.**

공개 계약은 그대로 둔다. 동기 경로의 호출자(`signal`)에게는 지금 형태가 정확하고, 비동기
consumer는 `minsu` 내부에 있으므로 내부 실행 계약을 따로 두면 된다. 내부 실행은 draft와 함께
outcome·재시도 가능 여부를 반환하고, 기존 내부 `GenerationOutcome`을 그대로 재사용한다.

| outcome | 판정 | 근거 |
| --- | --- | --- |
| `generated` | 성공 반영 | — |
| `generated_title_fallback` / `generated_truncated` | 성공 반영 | 모델 결과이며 정규화만 적용됨 |
| `timeout` | retryable | 일시적 지연일 수 있다 |
| `provider_error` | retryable | ADC·client 생성 실패와 quota 초과를 포함하며 복구 가능하다 |
| `invalid_response` | retryable | 같은 입력에도 모델 출력이 달라질 수 있다 |
| `invalid_output` | retryable | 위와 같다 |
| `insufficient_context` | terminal | 입력이 그대로이므로 재시도해도 같다 |
| `invalid_config` | terminal | 설정을 고치기 전에는 같다. 설정 수정은 배포 사건이므로 재시도로 기다리지 않는다. 다만 claim 자체가 설정 유효를 전제하므로(11.2절) 이 outcome은 claim 이후 설정이 무효해진 경합에서만 나온다 |
| `disabled` | 적재 안 함 | 비활성이면 원장을 만들지 않는다 (11절) |

`invalid_response`와 `invalid_output`을 retryable로 둔 이유는 structured output 위반이 결정적
오류가 아니기 때문이다. 다만 같은 입력으로 반복 실패하면 프롬프트·스키마 문제이므로
`retry_exhausted` 중 `failure.reason`별 건수와 구성비를 관측해 구분한다.

`provider_error`는 성격이 다른 실패를 한 값으로 묶는다. 일시 네트워크 오류나 일부 429·5xx는
재시도로 복구되지만, 잘못된 ADC·IAM 403·존재하지 않는 project/location은 설정을 고치기 전에는
같고, 일일 quota 소진은 짧은 백오프로 복구되지 않는다. 기존 `GenerationOutcome.PROVIDER_ERROR`
하나로는 이를 구분할 수 없다.

**첫 구현에서는 구분하지 않고 모두 제한 재시도한다.** 재시도 횟수에 상한이 있어 정합성 문제는
없고, 비용은 복구 불가능한 실패에 대한 몇 번의 헛된 호출과 그만큼 늘어난 `generating` 시간이다.
어느 쪽이 실제로 흔한지는 dev 측정 전에는 알 수 없으므로 지금 세분화하지 않는다. 관측에서
`retry_exhausted` 중 `provider_error` 비중이 크면 그때 category를 나눈다.

`invalid_config`는 terminal이지만 원장을 닫을 뿐 task는 고정 fallback을 유지하므로 손실이
없다. 설정을 고친 뒤 과거 건을 다시 생성할지는 운영 판단이며 이 설계에 넣지 않는다.

**설정이 무효한 동안 새로 들어온 작업은 이 표를 타지 않는다.** 11.2절이 정한 대로 scheduler가
claim 자체를 하지 않으므로 outcome이 만들어지지 않고, 원장은 `pending`으로 남는다. 이 표는
claim에 성공한 실행의 결과 판정이다.

`disabled`와 설정 무효는 원장에서 다르게 다뤄진다. 비활성이면 적재하지 않고, 설정이 무효면
적재는 하되 claim하지 않는다. 전자는 "이 기능을 쓰지 않는다"는 정상 상태라 원장에 남길 이유가
없고, 후자는 설정을 고치면 그대로 이어서 처리할 수 있는 작업이라 종료로 기록하면 되살릴 방법이
없기 때문이다(11.2절).

이 선택의 대가는 **설정 오류가 원장에는 드러나지 않는다**는 점이다. 쌓이는 것은 `pending`뿐이고
그것만으로는 scheduler 중단과 구분되지 않는다. 따라서 설정 오류의 1차 관측은 원장이 아니라
`momens.minsu.llm.config.valid` gauge가 담당한다(이미 있다). 9.3절의 "가장 오래된 미종료 원장의
age"는 원인을 가리지 않는 2차 지표로 함께 본다.

### 9.3 관측

동기 경로의 `momens.minsu.llm.generate` observation과 outcome/fallback reason tag를 그대로
쓰고, 비동기 경로임을 구분하는 low-cardinality tag를 추가한다. 여기에 원장 운영 지표를
더한다.

진행 상태:

- 미종료 원장 상태별 건수(`pending`/`processing`). `completed`를 포함하면 전체 테이블을 스캔해야
  하므로 제외하고, 미종료 부분 인덱스로 유계인 집합만 집계한다
- **가장 오래된 미종료(`pending`+`processing`) 원장의 age** — scheduler 중단을 보는 1차 지표.
  `pending`만 보면 claim 직후 멈춘 경우를 놓친다. 그때는 전부 `processing`이고 `pending`은 비어
  있다
- **만료된 `processing` lease 수와 최대 age**
- **`read_deadline_at`을 지난 미종료 원장 수** — deadline 투영 counter는 사용자가 실제 조회해야
  올라가므로 이 지표가 없으면 중단을 늦게 안다
- **scheduler heartbeat(마지막 성공 주기 시각)**
- **`next_attempt_at`부터 claim까지의 대기** — 빈 슬롯 수만큼만 claim하므로 claim 이후 모델 호출
  시작까지는 항상 0에 가깝고, 실제 scheduler 지연은 직전 배치가 다음 claim을 미루는 구간에 있다
- convert 커밋부터 반영까지의 end-to-end 지연

종료와 실패:

- `completion_reason`별 종료 건수와 그 원장들이 소모한 claim 수. 하나의 summary에서 `_count`와
  `_sum`으로 함께 본다
- `failure.reason`별 재시도 소진 건수
- **lease 만료로 회수된 작업 수**
- **claim token 불일치로 무시된 stale 결과 수**
- **task 하나당 실제 provider 호출 수** — 별도 지표를 두지 않고 provider observation의
  `_count`를 종료 summary의 `_count`로 나눠 복구 과정의 평균 중복 실행 비용을 본다(8.4절).
  진행 중 원장은 분모에서 빠지므로 비용 추정에 쓸 때 그만큼 과소 집계된다
- **deadline 투영으로 `ready`가 된 건수** — 0이 정상이며 경보 대상(8.6절)
- **원장 insert 실패율** — fail-closed 선택의 대가를 보는 지표(5.3절)

전체 종료 중 `retry_exhausted` 비율과 `user_edited` 건수는 제품 판단에 직접 쓰인다. 전자는
`completion.reason=retry_exhausted`인 summary의 `_count`를 모든 `completion.reason` summary의
`_count` 합으로 나눠 구한다. 전자가 높으면 모델·프롬프트 문제이고, 후자가 높으면 비동기 생성이
사용자보다 느려 가치가 없다는 신호다.

lease 만료 회수와 stale 결과 수는 함께 본다. 둘이 같이 오르면 lease가 실제 호출 시간보다
짧다는 뜻이므로 lease 갱신 도입 또는 lease 연장을 판단한다.

## 10. Agentic RAG 성립 여부

비동기 전환은 지연 예산의 상한을 **사용자 체감에서 원장 재시도 정책으로 옮긴다.** 8초라는
제약이 사라지므로 검색과 재검토를 반복하는 구조가 시간 측면에서는 성립한다.

다만 지금 성립하지 않는 것은 시간이 아니라 **재료**다. Agentic RAG는 반복 검색 대상이 있어야
하는데 `minsu`는 현재 retrieval에 연결돼 있지 않다. ADR-0014가 retrieval gRPC와 query
embedding을 명시적으로 제외했고, 검색 read-model은 `momens-retrieval`이 소유한다. 즉 지연
예산은 필요조건이었을 뿐 충분조건이 아니다.

정리하면 이렇다.

- 비동기 전환은 Agentic RAG의 **전제 조건**을 만든다. 이 티켓 범위는 여기까지다.
- 실제 도입은 `minsu → retrieval` 연동이 선행돼야 하며 별도 설계·티켓이 필요하다.
- 그 시점에 지연과 검색 품질의 타협점은 재시도 상한과 end-to-end 지연 관측값을 근거로
  다시 정한다. 지금은 근거가 될 데이터가 없다.

재검토 시점은 다음으로 둔다. **dev 활성화(11절 단계 2) 이후 end-to-end 지연과
`retry_exhausted` 비율이 쌓이면** 그 값을 근거로 `minsu → retrieval` 연동을 별도 설계로
착수할지 판단한다. 그때까지는 이 문서의 결론(전제 조건만 만든다)이 유효하다.

## 11. 단계적 전환

기존 동기 계약을 한 번에 끊지 않는다.

1. **동기 유지 + 원장 도입** — 비동기 생성을 기본 비활성으로 두고 원장·scheduler·관측만
   배포한다. 비활성이면 convert가 원장을 적재하지 않으므로 동작은 현재와 같다. 이 단계에서
   스케줄링 게이트를 먼저 정리한다(11.3절).
2. **dev 활성화** — dev에서 비동기를 켜고 end-to-end 지연, `retry_exhausted` 비율,
   `user_edited` 건수를 측정한다. 3구간 timeout 예산의 미측정 구간(4절)도 이때 함께 채운다.
3. **모바일 계약 확장** — `draft_status`를 응답에 추가한다. 원장 행이 없으면 항상 `ready`라
   앱은 단계 1·2에서도 안전하게 배포할 수 있다. 필드 추가 시점을 모바일에 미리 알린다.
4. **prod 활성화** — 다음이 모두 충족된 뒤 판단한다.
   - 데이터 레지던시 정책(동기 설계 11절)
   - dev 재측정으로 timeout·lease·deadline 값 확정
   - **worker의 `task.draft_generated` 소비 배포와 end-to-end projection 검증**(5.4절)
   - **`draft_status`를 처리하는 앱 배포**

마지막 항목은 계약 문제가 아니라 효과 문제다. `draft_status`를 모르는 기존 앱은 `generating`을
인식하지 못해 재조회하지 않는다. 서버가 AI title을 반영해도 사용자는 화면을 다시 열기 전까지
fallback을 본다. 비동기 생성을 켜도 사용자에게 도달하지 않으므로, 앱 지원 없이 prod를 켜는
것은 의미가 없다. 이는 모바일과 합의할 사항이 아니라 활성화 순서의 전제다.

### 11.1 rollout·rollback과 혼재 배포

비활성이면 **원장을 적재하지 않는다.** 활성 pod가 적재한 행만 존재하므로 원장 행의 존재가
비동기 대상 여부를 durable하게 표현한다. 이 성질이 혼재 배포와 rollback을 단순하게 만든다.

- **롤링 중 혼재** — 활성 pod가 적재한 작업은 원장에 남고, 어느 pod의 scheduler가 집어가도
  된다. 비활성 pod가 처리한 convert는 원장이 없어 동기 경로와 동일하다. 두 경우 모두 결과가
  정의돼 있다.
- **설정 rollback(flag off) 후 남은 `pending`** — 기본은 **계속 처리한다.** 이미 적재된
  작업은 유효하고, 처리해도 `tasks`는 유효한 draft를 유지한다. scheduler까지 멈춰야 하는
  상황이면 별도 스위치로 멈추고 원장은 남긴다. 이때도 앱은 `generating`에 갇히지 않는다.
  deadline이 지나면 읽기 투영이 `ready`로 닫기 때문이다(8.6절). 남은 행을
  `operationally_closed`로 즉시 닫는 절차는 지표를 깨끗하게 유지하기 위한 것이며 구현
  티켓에서 명령이나 쿼리로 구체화한다. `invalid_config`를 재사용하지 않는다. 실제 설정 오류
  지표가 오염된다.
- **바이너리 rollback** — `minsu` 원장·조회가 없는 이전 배포로 되돌리면 응답에
  `draft_status` 필드 자체가 없다. 앱은 이전 계약을 보므로 `generating`에 갇힐 대상이 없다.
  원장 행은 DB에 남아 있다가 다시 배포하면 이어서 처리되거나 deadline으로 닫힌다.
- **활성화 시점 이전 task** — 원장이 없으므로 `ready`다. 소급 생성하지 않는다.

각 단계는 앞 단계로 되돌릴 수 있다. 설정을 끄면 convert는 동기 경로로 돌아가고 `tasks`는
여전히 유효한 draft를 갖는다.

### 11.2 설정 축

11.1절의 동작(신규는 동기로 되돌리되 기존 `pending`은 계속 처리, 필요하면 scheduler만 정지)을
구현하려면 설정이 하나로는 부족하다. 현재 `momens.minsu.task-draft.enabled` 하나는 generator의
provider 호출 여부만 제어한다. 세 축으로 나눈다.

| 축 | 의미 | 끄면 |
| --- | --- | --- |
| 적재 | 신규 convert가 원장을 적재하는가 | 신규는 동기 경로. 기존 원장은 그대로 |
| drain | scheduler가 원장을 처리하는가 | 기존 원장이 멈춤. deadline 투영이 `ready`로 닫음 |
| provider | 모델 호출 자체가 활성인가 | 기존 `disabled` 의미. 적재도 하지 않음 |

이 분리가 없으면 rollback 시 `disabled`, `invalid_config`, `operationally_closed` 중 무엇으로
기록해야 하는지가 흔들린다. 설정명은 구현 티켓에서 정하되, **기존 원장이 남아 있는 상태에서
축이 꺼졌을 때의 동작은 여기서 확정한다.**

**provider가 비활성이거나 설정이 무효면 claim하지 않는다.** 원장은 `pending` 그대로 두고
`completion_reason`을 기록하지 않는다. 이 규칙이 9.2절의 `invalid_config` 판정보다 앞선다.
설정이 무효한 동안에는 claim이 없으므로 그 outcome 자체가 만들어지지 않는다. 재활성화되면 그대로 이어서 처리하고, 그 전에
`read_deadline_at`이 지나면 읽기 투영이 `ready`로 닫는다(8.6절).

`disabled`를 completion reason으로 만들지 않는 이유는 그것이 종료가 아니기 때문이다. 설정을
되돌리면 계속 처리할 수 있는 작업을 종료로 기록하면 되살릴 방법이 없다. 반대로 운영자가 명시적으로
끝내려는 경우는 `operationally_closed`가 이미 있다.

이 규칙 하나로 조합이 모두 결정된다. 롤링 배포로 활성 pod와 비활성 pod가 섞여도 마찬가지다.
비활성 pod는 claim하지 않고 활성 pod가 집어가며, 아무도 집지 않으면 deadline이 닫는다. 어느
경로든 `tasks`는 유효한 draft를 유지하고 앱은 `generating`에 갇히지 않는다.

### 11.3 스케줄링 게이트 정리 (MOM-0816에서 완료)

이 절의 초안은 `@EnableScheduling`을 `notification` 모듈이
`momens.notification.push.enabled`로 게이트하므로 **`minsu` scheduler가 push 꺼진 환경에서
조용히 동작하지 않는다**고 적었다. MOM-0816 구현 중 확인한 결과 **이 전제는 사실이 아니었다.**
`spring-modulith-starter-core`의 `MomentsAutoConfiguration`이 클래스 레벨
`@EnableScheduling`을 달고 있고 그 조건이
`@ConditionalOnProperty(name = "spring.modulith.moments.enabled", matchIfMissing = true)`라,
프로퍼티를 지정하지 않은 기본값에서 스케줄링 인프라가 push 설정과 무관하게 이미 켜져 있었다.
`notification`의 조건부 설정은 스케줄링을 실제로 끄지 못했다.

그럼에도 게이트는 옮겼다. 다른 모듈 스케줄러의 동작 보장이 **무관한 라이브러리 auto-config의
부수효과에 얹혀 있었기 때문이다.** 의존성이 빠지거나 저 프로퍼티를 끄면 소리 없이 사라지고,
그때 실패는 원장만 쌓이는 형태로 나타난다.

확정된 배치는 다음과 같다.

- 스케줄링 인프라 활성화는 조립 모듈 `app`이 소유하고 항상 켠다
  (`works.momens.server.support.scheduling.SchedulingConfig`). `common`은 기능 모듈이 의존해야
  하는 공유 코드용이고, 활성화는 조립 결정이므로 `app`에 둔다.
- 모듈별 실행 여부는 각 모듈이 자신의 설정으로 판정한다. `notification`은
  `NotificationPushScheduler`의 `@ConditionalOnProperty`로 push 비활성 시 빈 자체가 등록되지
  않는다.
- 따라서 `minsu` scheduler는 push 설정과 무관하게 자신의 설정 축(11.2절)만으로 동작 여부가
  정해진다.
- Modulith의 `MomentsAutoConfiguration`은 `spring.modulith.moments.enabled=false`로 끈다
  (`app/src/main/resources/application.yml`). Moments의 시간 이벤트는 이 앱에 리스너가 없어
  잃는 기능이 없고, 이렇게 해야 활성화 주체가 `SchedulingConfig` 하나가 된다. 이 설정을 빼면
  `SchedulingConfig`가 없어도 스케줄링이 켜져 있어 소유 관계가 코드로 표현되지 않는다.

이 배치는 `SchedulingConfig`를 실제 의존 지점으로 만든다. 이 빈이 빠지면 push 폴링이 조용히
멈추므로, 그 계약은 `SchedulingConfigIntegrationTest`가 CI에서 가드한다.

주의: 기본 `TaskScheduler`는 풀 크기가 1이라 모든 `@Scheduled`가 한 스레드에서 직렬 실행된다.
`minsu` drain은 LLM 호출로 길어질 수 있어 1초 주기 push 폴링을 막을 수 있다. 원장·scheduler
구현 티켓에서 `spring.task.scheduling.pool.size`를 함께 정한다.

## 12. 후속 구현 티켓

이 문서는 설계만 확정한다. 구현은 다음으로 분리한다.

1. **원장·scheduler 기반** — `minsu` 생성 원장 테이블, convert 트랜잭션 적재 API, draft 확보
   진입점 분리(5.5절), claim token과 lease, 재시도 판정, 조건부 UPDATE 반영과 terminal 전이의
   원자성, 원장 나이 상한, scheduler 풀 크기(11.3절), 관측. 기본 비활성. 스케줄링 게이트
   정리는 MOM-0816으로 분리해 완료했다.

   이 티켓은 `minsu`를 **무상태 모듈에서 영속성 소유 모듈로 바꾼다.** 현재
   `modules/minsu/build.gradle`에는 JPA·DB 의존이 전혀 없다(validation, actuator, jackson,
   google-genai). 모듈 의존 추가와 `common`의 영속성 기반 준수가 범위에 포함된다.

   `project`의 반영 API는 **호출자 트랜잭션에 참여해야 한다.** `minsu`가 트랜잭션을 열고 CAS
   반영과 원장 terminal 전이를 함께 커밋하기 때문이다(8.3절). 구체적으로는 다음을 지킨다.

   - 같은 transaction manager를 쓰고 기본 `PROPAGATION_REQUIRED`로 참여한다. 반영 API에
     `@Transactional`을 붙이는 것 자체는 문제가 아니다. 기본 전파가 이미 참여이고 현재
     `TaskWriterImpl.update`도 같은 방식이다.
   - `REQUIRES_NEW`, 별도 transaction manager, 명시적 조기 commit은 금지한다.
   - CAS가 0건이거나 원장의 token·deadline 조건 갱신이 0건이면 예외 없이 커밋하지 않고 전체를
     롤백한다. task만 바뀌고 원장이 남는 상태를 만들지 않는다.

     이 문장이 금지하는 것은 **부분 커밋**이지 baseline 불일치 자체가 아니다. 8.1절이 정한
     `user_edited`·`task_gone` 종료는 "반영하지 않고 원장을 닫는" 정상 경로이며, 반영과 종료가
     같은 트랜잭션 안에 있으므로 위 조건을 어기지 않는다. 구현(MOM-0820)은 반영 API가 조건
     불일치를 **쓰기 전에** 판정해 결과로 돌려주므로 "CAS 0건" 상태가 아예 생기지 않는다.
     위 문장은 반영이 커밋됐는데 원장 전이가 빠지는 조합을 막는 규칙으로 읽는다.
   - 반영에 성공하면 같은 트랜잭션에서 `task.draft_generated`를 append한다(5.4절).

   조건부 UPDATE를 native·JPQL bulk update로 구현하면 JPA Auditing이 동작하지 않아
   `tasks.updated_at`이 갱신되지 않는다. `docs/rules/persistence.md`가 수정 테이블의
   `updated_at`을 Auditing으로 관리하도록 정하므로, CAS SQL에서 `updated_at`을 DB 시각으로 함께
   갱신하거나 row lock 후 entity mutation으로 구현한다.

2. **모바일 계약 확장** — `draft_status` 응답 필드와 `mobile → minsu` 읽기 의존, 7.3절의 읽기
   순서 계약. API 명세를 갱신하고 필드 추가를 모바일에 알린다. 앱 decoder가 unknown field를
   거부하면 서버 단독 선배포가 앱을 깨뜨리므로, **현재 앱 decoder의 unknown field 동작 확인과
   그에 따른 배포 순서 결정**을 이 티켓의 완료 조건에 포함한다.

3. **worker의 `task.draft_generated` 소비** (`momens-worker`) — 새 event_type 소비, watermark
   호환, 재-hydrate, end-to-end projection 검증. **prod 활성화의 선행 조건**이며(5.4절) 서버
   구현과 독립적으로 진행할 수 있다.
4. **dev 측정** — end-to-end 지연, `retry_exhausted` 비율, `user_edited` 건수, 3구간 timeout
   미측정 구간.
5. **prod 마이그레이션** — `momens-api`에 원장 테이블 마이그레이션 PR.

ADR-0015가 이 PR에 함께 들어가므로 별도 ADR 티켓은 두지 않는다.

검증 기준은 각 티켓이 소유한다. 이 문서는 어떤 값이 근거를 갖춰야 하는지만 정한다.

## 13. 미확정으로 남는 것

- attempt 상한·lease 값과 실행 동시성 — MOM-0819에서 **잠정값**을 정했다(아래 표). 확정은
  MOM-0824의 dev 재측정이며 9.1절의 부등식은 그때도 유지한다. `apply_cutoff_at`·
  `read_deadline_at`은 MOM-0818에서 정했다(8.6절).
- 입력 snapshot의 description·impact 상한과 보존·삭제 정책 — **prod 마이그레이션 전에 확정**
  (5.6절). 정책 없이 prod에 가면 `signals` 본문이 두 벌 남고, 지우려면 그때는 운영 데이터라
  삭제 마이그레이션이 필요하다. baseline을 원장이 따로 소유하므로(6절) snapshot만 비우는
  정리가 가능하다.
- prod 데이터 레지던시 정책 — 동기 설계 11절에서 이월된 미결
- `minsu → retrieval` 연동 — 별도 설계

### 13.1 MOM-0819에서 확정한 값

`momens.minsu.task-draft.async.execution.*`이고 전부 잠정값이다. 근거가 되는 실측은 동기
경로의 8초(MOM-0806) 하나뿐이며 그 값은 원탭 action의 사용자 체감 기준이라 비동기의 근거가
되지 못한다. 그래서 **넉넉한 쪽**으로 잡았다. 값이 짧으면 정상 호출이 잘려 재시도로 낭비되고
`retry_exhausted`가 올라 측정 자체가 오염되는 반면, 길면 `generating` 노출이 길어질 뿐이고 그
구간에도 사용자는 유효한 fallback title을 본다.

| 값 | 잠정값 | 근거 |
| --- | --- | --- |
| `provider-timeout` | 20s | 동기 8초의 2.5배. 요청별 `httpOptions`로 걸어 동기 경로와 분리한다 |
| `attempt-timeout` | 30s | SDK가 먼저 끊도록 여유를 두되 ADC 탐색·client 생성까지 감싼다 |
| `lease` | 60s | attempt 상한의 2배. 정상 실행 중 만료가 구조적으로 일어나지 않는다 |
| `backoffs` | 10s, 30s, 2m | `push_deliveries`의 1s/5s/30s보다 길다. LLM은 즉시 재시도가 의미 없다 |
| `max-attempts` | 4 | `push_deliveries`와 같다 |
| `concurrency` | 4 | 멈춘 호출이 묶을 수 있는 슬롯의 상한이기도 하다(9.1절) |
| drain 주기 | 1s | 원장을 늦게 집을수록 `generating` 노출이 길어진다. 단 claim 간격의 하한은 아니다(아래) |

부등식은 `30s < 60s < 55m < 60m`으로 성립한다. 최악의 총 소요는 시도 4회(120초)와 백오프
대기(2분 40초)를 합쳐 약 4분 40초이므로 `apply_cutoff_at`까지 열 배 이상 여유가 있다. 이
부등식은 부팅 시점에 `MinsuAsyncProperties`가 강제한다.

**drain 주기는 claim 간격의 하한이 아니다.** `fixedDelay`는 한 패스가 끝난 뒤부터 세고 패스는
배치의 결과 기록까지 블로킹하므로, 실제 하한은 직전 배치에서 가장 느린 시도와 결과 기록에 주기를
더한 값이다. 그 사이 빈 슬롯이 있어도 새로 적재된 원장은 집히지 않으며, 배치 전체가 하나의
wall-clock 상한을 공유하므로 이 지연의 상한은 `attempt-timeout`이다. `notification`의
`PushSender`처럼 패스 안에서 claim을 반복하는 방식은 듣지 않는다. 그쪽은 발송 슬롯이 아니라 배치
크기가 한계지만, minsu는 배치가 슬롯을 다 채운 상태라 라운드를 더 돌려도 claim이 0이다. 지연을
실제로 줄이려면 결과 기록을 워커 스레드로 내려 패스를 논블로킹으로 만들어야 하고, 그것은 MVP
물량에서 정당화되지 않는다. 값의 재측정과 함께 MOM-0824에서 다시 판단한다.

**lease 갱신은 두지 않는다**(8.2절이 구현 티켓으로 넘긴 항목). 8.2절이 단 조건("갱신하지 않으면
lease를 비동기 timeout보다 충분히 크게")을 attempt 상한의 두 배인 lease가 충족한다.
`notification`의 `renewLease`는 event 그룹 단위 배치 발송이라 claim과 발송 사이가 벌어져서
필요했고, minsu는 건당 실행이라 그 간격이 없다.

**scheduler 스레드 풀 크기는 5다**(11.3절이 넘긴 항목, MOM-0821에서 확정).
`spring.task.scheduling.pool.size`이며, 기본값 1이면 minsu drain과 `notification`의 1초 push
폴링이 한 스레드에서 서로를 밀어낸다.

`@Scheduled` 빈이 넷이다 — push 폴링, minsu drain, minsu 지표 스냅샷, minsu 원장 종료(13.2절).
앞의 둘은 오래 블로킹한다(drain은 배치의 wall-clock 상한까지, push는 FCM 발송까지). 풀이 빈 수보다
작으면 뒤의 둘이 그만큼 밀리는데, **하필 drain이 느린 상황이 그 둘이 존재하는 이유**라 관측이
필요한 순간에 관측이 멈춘다. 하나를 더 두는 것은 그 실패가 조용해서다. 다른 모듈이 폴러를 붙일 때
이 값을 볼 사람이 minsu 담당자가 아니다.

### 13.2 남은 `pending`을 닫는 절차 (MOM-0821에서 확정)

11.1절이 구현 티켓으로 넘긴 항목이다. 설정 rollback 뒤 남은 원장은 **기본적으로 계속
처리한다.** 앱은 이 절차 없이도 `generating`에 갇히지 않는다. `read_deadline_at`이 지나면 읽기
투영이 `ready`로 닫기 때문이다(8.6절).

**반영 창이 닫힌 행은 주기 작업이 자동으로 닫는다.** 초안은 이 절을 운영자 수동 SQL로만 정의했으나
그 형태로는 성립하지 않는다. 두 claim 쿼리가 `apply_cutoff_at > NOW()`를 요구하므로 그 시각을 지난
행은 **영영 집히지 않고**, 종료 전이는 claim된 행에만 일어난다. 아무도 SQL을 실행하지 않는 동안
그 행들이 미종료로 쌓여 9.3절 상태 지표가 현재 적체와 버려진 행을 구분하지 못하고, "미종료 집합은
처리량에 묶여 유계"라는 전제도 깨진다.

- 사유는 `operationally_closed`가 아니라 **`deadline_exceeded`**다. 전자는 운영자가 명시적으로
  끝내는 경우이고, 이건 나이 상한 초과라 8.6절이 이미 쓰는 사유와 같다. 자동 회수에 전자를 쓰면
  운영자 판단으로 닫은 건수를 구분할 수 없게 된다.
- **lease가 살아 있는 `processing`은 닫지 않는다.** 지금 실행 중이고 그 결과는 결과 기록의 cutoff
  분기가 닫는다. 먼저 닫으면 claim token이 지워져 그 결과가 stale로 잘못 집계된다. 워커가 죽었으면
  lease가 만료되므로 다음 주기에 들어온다. 이 조건은 두 claim 쿼리와 교집합이 없어 경합하지 않는다.
- **`drain` 축에 걸지 않는다.** 축을 끈 뒤 남은 `pending`이 cutoff를 지나는 것이 바로 이 절차가
  필요한 상황이라, 같은 조건을 걸면 정작 그때 돌지 않는다.
- bulk update가 아니라 행을 잠그고 엔티티를 바꾼다. bulk는 JPA Auditing을 우회해 `updated_at`이
  멈추고(`docs/rules/persistence.md`), 무엇보다 종료 지표가 이 경로만 비게 된다.

아래 수동 SQL은 **아직 반영 창 안에 있는 행까지** 즉시 끝내야 할 때만 쓴다. 자동 절차는 창이 닫힌
뒤에만 개입하므로 그 구간은 덮지 않는다. admin API로 만들지 않았다. 운영자가 상황을 보고 한 번
실행하는 조치이고, 상시 진입점을 두면 그 자체가 잘못 눌릴 수 있는 표면이 된다.

```sql
-- 1. 대상 확인. 반드시 먼저 실행한다.
SELECT status, count(*), min(created_at) AS oldest
  FROM minsu_task_draft_generations
 WHERE status <> 'completed'
 GROUP BY status;

-- 2. 종결. claim 보유 중인 행도 함께 닫으므로 claim CHECK를 만족하도록 token과 lease를 비운다.
UPDATE minsu_task_draft_generations
   SET status = 'completed',
       completion_reason = 'operationally_closed',
       claim_token = NULL,
       lease_expires_at = NULL,
       updated_at = NOW()
 WHERE status <> 'completed';
```

- **`drain` 축을 먼저 끄고 실행한다.** 켜진 채로 닫으면 이미 claim된 실행이 결과를 기록할 때
  token 불일치로 무시되기는 하지만, 그때까지 provider 호출이 계속 나간다.
- `invalid_config`를 재사용하지 않는다. 실제 설정 오류 지표가 오염된다.
- `updated_at`을 직접 갱신하는 것은 이 문장이 JPA Auditing을 거치지 않기 때문이다
  (`docs/rules/persistence.md`).
- 닫은 뒤 되살릴 방법은 없다. 그래서 이것이 `disabled`를 종료 사유로 만들지 않은 이유와 같은
  선택의 반대편이다(11.2절).
