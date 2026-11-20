# 0015. Minsu task draft 비동기 생성과 `minsu` 영속성 소유

- 상태: Accepted
- 날짜: 2026-08-01
- 작성자: Kimgyuilli

[ADR-0014](0014-minsu-task-draft-module-and-llm-boundary.md)를 **부분적으로** supersede 한다.
바뀌는 것은 draft 생성 시점, draft 저장, 모듈 의존 방향이다. `minsu` 모듈을 두는 결정, 벤더
중립 `LlmClient` port, Google adapter와 SDK 사용 방침, 응답 검증 순서와 고정 fallback 정책,
model 선택 policy는 그대로 유효하며 이 ADR이 다시 정하지 않는다.

[ADR-0011](0011-signal-evidence-and-task-draft-contract.md)은 **부분적으로** supersede 한다.

| ADR-0011 | 이 ADR |
| --- | --- |
| draft는 convert 시점에 생성한다 | convert가 생성을 **시작**하고 실제 모델 생성은 커밋 이후다. 사용자가 convert를 누른 시점의 입력을 쓰므로 근거의 시점은 그대로다 |
| draft를 저장하지 않으므로 schema 추가는 `signal_evidence`에 한정된다 | `minsu`가 생성 원장을 소유한다. 입력 snapshot과 반영 baseline을 저장한다 |
| outbox 경계와 payload는 ADR-0008·0010 변경이 필요하지 않다 | `task.draft_generated`를 추가한다 |

ADR-0011의 나머지는 그대로 유효하다. `signal_evidence`의 대상·변화·영향 생산 계약, 세 값의
30자 제한, convert가 body 없는 원탭 action이라는 점, draft의 role·priority 허용값, task draft를
모바일 상세에 노출하지 않는다는 결정은 바뀌지 않는다.

## 맥락

ADR-0014는 convert-to-task 요청 경로에서 동기로 draft를 생성하기로 했다. 당시에는 실제 지연을
몰랐고, 첫 슬라이스를 작게 유지하는 것이 우선이었다. ADR-0014 자신도 "초기에는 외부 호출
latency가 그대로 사용자 요청 시간에 더해지고 명시적 timeout이 없다"고 그 대가를 적어뒀다.

MOM-0806의 로컬 측정으로 그 대가가 구체화됐다. warm provider 구간은 p50 1,258ms이지만 p95
5,960ms / max 6,161ms로 tail 변동이 컸고, 30건 중 3건이 5,000ms를 넘었다. 팀 합의로 8초
timeout을 넣었지만 이는 상한을 그은 것이지 지연을 줄인 것이 아니다.

세 가지가 드러났다.

- **지연 예산이 응답 시간에 묶여 있다.** 8초는 원탭 action의 사용자 체감 기준이라, 검색·재검토를
  반복하는 생성 구조를 이 예산 안에 넣을 수 없다.
- **8초가 실제 상한이 아니다.** `HttpOptions.timeout`은 OkHttp `Call` 구간에만 걸리고 ADC 탐색·
  client 생성·token 갱신은 그 밖이다. 앱(OkHttp 기본 10초)이 서버보다 먼저 끊는 창이 있다.
- **지연이 큰 Signal일수록 fallback 확률이 높다.** context가 길고 evidence가 많은 Signal이
  모델 생성의 가치가 큰 쪽인데, 바로 그쪽이 timeout에 걸린다.

동시에 ADR-0014가 남긴 미결이 있다. "동시 최초 요청은 둘 다 모델을 호출할 수 있다 … 별도
단일 비행 또는 reservation 설계를 검토한다."

## 결정

### 생성 시점

**비동기 활성 시 convert 요청 경로에서 LLM을 호출하지 않는다.** convert는 고정 fallback draft로
task를 만들어 즉시 응답하고, 풍부한 draft는 커밋 후 별도로 생성해 반영한다.

이에 따라 ADR-0014의 다음 결정을 대체한다.

| ADR-0014 | 이 ADR |
| --- | --- |
| 새 convert는 쓰기 트랜잭션 밖에서 동기로 draft 생성 | 비동기 활성 시 요청 경로에서 LLM 미호출. 비활성이면 기존 동작 유지 |
| task·action·outbox만 원자 저장 | 생성 원장 적재를 같은 트랜잭션에 포함 |
| task draft를 저장하지 않으므로 DB·Flyway 변경 없음 | `minsu`가 생성 원장 테이블을 소유. Flyway 변경 있음 |
| 동시 최초 요청의 중복 LLM 호출을 허용하고 단일 비행은 후속 검토 | 정상 경로의 중복 호출이 사라진다. 요청 경로에서 모델을 부르지 않고 원장 행이 한 건이기 때문이다 |

마지막 항목은 ADR-0014가 남긴 미결의 해소다. 다만 이는 **정상 경로**에 한정된다. lease 만료와
복구 과정에서는 중복 실행이 여전히 가능하며, claim token은 결과 기록을 fencing할 뿐 외부 호출을
exactly-once로 만들지 않는다.

### 작업 유입과 원장 소유

`minsu`가 생성 원장 테이블을 소유하고, convert 트랜잭션이 `pending` 행을 함께 적재한다.
`minsu` scheduler가 원장을 직접 claim해 실행한다.

기존 `outbox_events`를 소비하는 안은 채택하지 않았다. 공개 `OutboxEventView`에 payload가 없어
`task_id`를 얻을 수 없고, `notification`의 최초 watermark 시드 정책을 재사용하면 시드 이전
작업이 유실되며, 커밋과 materialize 사이에 상태를 판정할 원장이 없다.

원장 적재는 fail-closed다. 적재가 실패하면 convert 전체가 롤백된다. fail-open은 실패가 조용해
그동안의 task가 풍부화되지 않은 것을 아무도 알 수 없다.

### projection 전달

ADR-0008의 outbox·worker projection 경계는 유지한다. api-server는 여전히 projection을 직접 쓰지
않고 outbox row만 남긴다. 다만 **event를 하나 추가한다.**

convert는 fallback 값으로 task를 만들며 `task.created`를 발행하고, worker는 이를 소비해
projection을 만든다. AI 결과는 그 뒤 별도 트랜잭션에서 반영되므로 후속 event가 없으면
projection이 fallback title로 영구 잔류한다. safety lag는 근거가 되지 못한다. LLM 호출과
재시도는 초 단위라 정상적인 경우 worker가 먼저 소비한다.

따라서 반영 트랜잭션에서 `task.draft_generated`(aggregate `task`, payload 없음)를 함께
발행한다. ADR-0010의 `{aggregate}.{과거형 동사}` 규약을 따르고 한 task당 최대 한 번만
발행되므로 `event_type + aggregate_id` 멱등키와 맞는다. `tasks`를 실제로 갱신했을 때만 발행하며,
사용자 편집·삭제·재시도 소진처럼 `tasks`가 그대로인 종료에서는 발행하지 않는다.

ADR-0008의 MVP event 목록에 이 event가 추가된다. `minsu → outbox` 의존이 함께 생긴다.

worker가 `task.created`를 원장 terminal까지 대기·재시도하는 대안은 채택하지 않았다. worker가
`minsu` 원장 스키마를 알아야 해 결합이 훨씬 크다.

### 모듈 경계

ADR-0014와 동기 상세 설계가 함께 전제한 "`minsu`는 `signal`, `project`, `mobile`에 의존하지
않는다"를 다음으로 바꾼다.

- `signal → minsu` — 기존. draft 확보, 원장 적재, 상태 조회
- `minsu → project` — **신규.** 생성 결과를 `tasks`에 반영. 참조 범위는 draft 반영 하나로 제한
- `minsu → outbox` — **신규.** 반영 시 `task.draft_generated` 발행
- `mobile → minsu` — **신규.** task 상세의 생성 상태 조회

`project → minsu`는 `minsu → project`와 순환이므로 금지한다. 상태를 조합하는 것은 표면 모듈인
`mobile`의 책임이다.

### `docs/rules/architecture.md` 기본값에 대한 예외

이 결정은 문서화된 두 기본값에서 벗어난다. 둘 다 금지가 아니라 기본값·최소화이고,
architecture.md 자신이 "단순한 경우 상대 모듈의 public API 직접 참조를 허용하되 리뷰 단계에서
세부 논의합니다"라고 적어둔 자리가 여기다. 규칙 자체는 바꾸지 않고 **범위를 한정한 예외**로
둔다.

| architecture.md 기본값 | 이 ADR |
| --- | --- |
| "기본은 application event 기반 협력" | Modulith event publication registry 대신 public API 직접 호출. 이 레포는 아직 application event를 도입하지 않아 `event_publication` Flyway와 재발행 스케줄러를 새로 만들어야 하는데, 원장이 이미 durable queue라 얻는 것이 없다 |
| "트랜잭션 단위는 같은 도메인에 닫고, 모듈 경계를 넘는 트랜잭션 참여는 최소화" | `minsu`가 연 트랜잭션에 `project` 반영 API가 참여한다 |

예외 범위는 **`minsu → project`의 draft 반영 하나**다. 다른 모듈 조합이나 `minsu`의 다른
유스케이스로 확대하지 않는다. 트랜잭션을 넘기는 이유는 아래 원자성 논거 하나뿐이므로, 그
조건이 사라지면 예외도 사라진다.

이 변경으로 `minsu`는 무상태 모듈에서 **영속성 소유 모듈**이 된다. 또한 `minsu`가 연 트랜잭션에
`project`의 반영 API가 참여하는 cross-module 트랜잭션이 생긴다. 이를 허용하는 이유는 `tasks`
반영과 원장 종료가 원자적이지 않으면, 반영 후 프로세스가 죽었을 때 재시도가 CAS 0건을 보고
사용자 편집으로 오판하기 때문이다. 단일 datasource 모듈리스라 분산 트랜잭션 문제는 없다.

### 공개 계약

`minsu` 공개 계약에 세 진입점을 둔다. 활성 여부 판정은 `minsu`가 소유하고 `signal`은 알지
못한다.

- **draft 확보** — 쓰기 트랜잭션 밖. 활성이면 LLM을 부르지 않고 고정 fallback을 반환한다.
- **원장 적재** — 쓰기 트랜잭션 안. 활성일 때만 `pending` 행을 만든다.
- **상태 조회** — `signal`(replay)과 `mobile`(task 상세)이 쓴다. deadline 투영을 내장하는 유일한
  지점이라 판정 로직이 한 곳에 있어야 한다.

현재 `SignalTaskDraftGenerator.generate`는 비활성·설정 무효·입력 부족이 아닌 한 항상 provider를
호출하므로, 이 메서드 하나로는 "활성일 때 LLM을 부르지 않는다"를 표현할 수 없다. draft 확보와
원장 적재를 나누는 것은 트랜잭션 경계가 다르기 때문이기도 하다.

모바일 응답에는 생성 상태를 `generating`과 `ready` 두 값으로만 노출한다. 종료 사유는 원장과
관측에만 남긴다. ADR-0011과 ADR-0014의 "fallback 여부와 이유를 public API에 넣지 않는다"를
유지한다.

## 대안

- **동기 유지 + timeout만 조정.** 변경이 없지만 지연 예산이 계속 응답 시간에 묶이고, timeout을
  늘리면 사용자가 그만큼 기다린다. 근본 제약이 그대로다.
- **`@Async`로 커밋 후 실행.** 가장 단순하지만 작업이 JVM 힙에만 존재해 배포·OOM·eviction으로
  유실되고 복구 진입점이 없다. 롤링 배포마다 일부 task가 조용히 fallback으로 남는다.
- **Modulith event publication registry 사용.** 커밋 시점 보장과 복구 진입점을 얻지만 이 레포는
  아직 application event를 도입하지 않아 `event_publication` Flyway와 재발행 스케줄러를 새로
  만들어야 한다. 원장 대비 이점이 없다.
- **`tasks`에 생성 상태 컬럼 추가.** 읽기가 단순하지만 사용자가 편집하는 row와 운영 원장이
  섞이고, 생성 상태 소유가 `project`로 넘어간다.
- **`minsu → signal`을 허용해 실행 시점에 Signal을 다시 읽기.** 원장에 입력을 복제하지 않아도
  되지만 의존이 하나 더 늘고, Signal이 그사이 수정되면 사용자가 convert를 누른 시점과 다른
  근거로 draft가 만들어진다.
- **`signal`이 비동기 실행까지 조립.** ADR 변경이 없지만 Signal action의 멱등·충돌 정책을 맡는
  모듈이 LLM 작업의 claim·재시도·백오프 운영까지 떠안는다.

## 결과

convert 응답에서 LLM 지연이 빠져 동기 구간은 DB 쓰기만 남는다. 앱·ingress timeout과 경쟁하지
않고, 늘어난 지연 예산은 이후 검색 반복 구조의 전제 조건이 된다.

`tasks`는 convert 시점부터 형식이 유효한 draft를 갖는다. 비동기 생성이 전부 실패해도 사용자가
보는 것은 기존 동기 경로의 fallback과 같다. 가용성의 하한은 내려가지 않는다. 다만 이는 형식과
가용성의 하한이며, schema를 통과한 모델 결과가 Signal title보다 나쁠 수 있다는 위험은 동기
경로와 동일하게 남는다.

대신 다음을 감수한다.

- `minsu`가 영속성과 스케줄링을 소유하게 되어 모듈이 무거워진다.
- 원장 장애가 convert API 장애가 된다(fail-closed).
- Signal 입력이 원장에 복제된다.
- 모바일 계약에 생성 상태 필드가 추가되고, 앱은 재조회로 결과를 확인해야 한다.
- 복구 과정의 중복 LLM 실행과 그 비용이 남는다.

세부 설계는 [MOM-0810 당시 비동기 task draft 생성 설계](https://github.com/momens-works/momens-server/blob/93a8f733ffdfa5220348be321455ece5dd345d9e/docs/design/minsu-async-task-draft-design.md)에
있다. 운영 timeout·백오프·lease·deadline 값은 dev 재측정 후 확정하며, 그 전에는 prod에서
비동기 생성을 활성화하지 않는다.
