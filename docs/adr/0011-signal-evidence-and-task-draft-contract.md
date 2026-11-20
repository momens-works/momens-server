# 0011. Signal evidence와 task draft 생산·저장 계약

- 상태: Accepted (draft 생성 시점·저장·outbox 부분은 [ADR-0015](0015-minsu-async-task-draft-generation.md)로, prod 스키마 소유권은 [ADR-0019](0019-prod-schema-ownership-transfer.md)로 대체)
- 날짜: 2026-07-11
- 작성자: Kimgyuilli

> 운영 공유 DB의 schema 변경을 `momens-api` 마이그레이션이 소유한다는 아래 결정은
> [ADR-0019](0019-prod-schema-ownership-transfer.md)로 대체되었습니다. 현재 DDL 소유 범위는
> 해당 ADR과 [데이터 규칙](../rules/persistence.md#레거시worker-ddl-동결)을 따릅니다.

## 맥락

ADR-0007은 worker가 신규 `signals`와 `signal_evidence`를 생성하고 api-server가 이를
조회·action 대상으로 사용하도록 경계를 정했다. 초기 MVP 상세 응답은 `source_refs`에서 원천 정보를
hydrate하고 `evidence.fields`는 빈 배열로 두었으며, api-server가 Signal 제목과 중립 priority를 조합해
최소 task draft를 만들었다.

최신 화면설계서의 Signal 상세는 근거마다 provider와 무관한 `대상`, `변화`, `영향`을 요구하고, 각 값을
공백 포함 30자 이하로 제한한다. 또한 `convert-to-task`는 사용자가 role을 추가로 선택하지 않는 원탭
action이어야 한다. task의 role은 필수값이므로 원탭 action에는 title·role·priority가 채워진 draft가
필요하다.

Minsu suggestion과 task draft는 worker 산출물이 아니라 민수 산출물이다. 민수는 서버 내 모듈로 구현할
계획이며, MVP 시점에는 아직 구현되지 않는다. worker 역시 준비되지 않은 MVP 환경에서도 모바일·서버
계약은 worker/민수 연결 여부에 따라 달라지지 않아야 한다.

> **2026-08-01**: task draft의 **생성 시점·저장·outbox** 관련 결정은
> [ADR-0015](0015-minsu-async-task-draft-generation.md)가 대체했다(커밋 이후 비동기 생성,
> `minsu` 생성 원장 저장, `task.draft_generated` 추가). evidence 생산 계약과 모바일 응답
> 계약은 그대로 유효하다.

## 결정

`signal_evidence`는 Signal과 source의 관계에서 생성된 의미 정보인 `대상`, `변화`, `영향`을 소유한다.
세 값은 worker가 Signal을 생성할 때 함께 생산하고, worker가 준비되지 않은 MVP 환경에서는 같은
backing 계약을 따르는 fixture가 채운다.

- 각 값은 생산 단계에서 공백을 포함해 30자 이하로 제한한다(화면설계서 기준).
- api-server는 값을 생성하거나 자르지 않고 저장된 값을 그대로 반환한다.
- `source`, `occurred_at`, `source_url` 같은 원천 정보는 ADR-0007대로 `source_refs`에서 hydrate한다.
  근거 행 헤더는 도구 이름(`source`)으로 표시하므로 `source_title`은 모바일 계약에 포함하지 않는다.
- 모바일 API는 세 값이 고정 의미이므로 generic label/value 목록이 아니라
  `details.target`, `details.change`, `details.impact`로 반환한다.

task draft(title, role, priority)는 민수 산출물이며, Signal 생성 시점이 아니라 태스크 등록
(`convert-to-task`) 시점에 생성한다. `signals` backing에는 draft를 저장하지 않는다.

- `POST /api/mobile/signals/{signalId}/actions/convert-to-task`는 요청 body를 받지 않는 원탭 action이다.
- draft의 `role`은 `pm`, `design`, `backend`, `frontend` 중 하나, `priority`는 `low`, `medium`, `high` 중
  하나다.
- 민수가 구현되기 전 MVP에서는 api-server가 고정 목 draft를 사용한다: `title`은 Signal title,
  `role`은 `pm`, `priority`는 `medium`. 고정 값이므로 요청마다 결과가 달라지지 않는다.
- Minsu suggestion도 같은 민수 산출물이며 민수 구현 전에는 목으로 처리한다.
- task draft는 모바일 상세 화면에 노출하지 않는다.

모바일 Signal 상세 응답은 화면 렌더링에 필요한 값만 제공한다. project·description·task draft·가능한
action 목록은 서버 내부 상태 또는 고정 UI 정책이므로 응답에서 제외한다. 처리된 Signal을 다시 보는
inbox가 MVP 범위 밖이므로 상세 조회도 미처리 Signal만 대상으로 한다.

운영 공유 DB의 schema 변경은 기존 규칙대로 `momens-api` 마이그레이션이 소유하고,
`momens-server`는 local/test mirror와 read mapping을 맞춘다. draft를 저장하지 않으므로 schema 추가는
`signal_evidence`의 세 의미 컬럼에 한정된다.

## 대안

- `source_refs`에 대상·변화·영향 저장: 같은 source가 여러 Signal의 근거가 될 수 있고 Signal 맥락마다
  의미가 달라질 수 있어 부적합하다.
- worker가 draft를 생산해 `signals` backing에 저장: draft는 worker가 아니라 민수 산출물이고, 태스크
  등록 시점에 생성하면 충분하다. Signal 생성 시점 저장은 운영 schema 변경 범위만 키운다.
- api-server가 상세 조회 시 evidence 문구를 추론: 요청마다 결과가 달라질 수 있고 api-server가 생성
  책임을 갖게 되어 worker와의 경계 및 임의생성 금지 원칙에 어긋난다. 반면 convert 시점의 고정 목
  draft는 민수 경계의 한시적 대역이고 결정적이므로 이 원칙의 명시적 예외로 둔다.
- 모바일이 role을 선택해 convert 요청에 포함: 구현은 단순하지만 원탭 제품 요구사항과 맞지 않는다.
- 기존 generic `fields[]` 유지: 세 항목이 고정 의미인데도 문자열 label과 순서에 의존하게 되어 클라이언트
  계약이 불명확하다.

## 결과

evidence 의미 값은 worker(또는 같은 계약의 fixture)가, suggestion과 draft는 민수(구현 전에는 목)가
생산하므로 환경에 따라 모바일 API가 달라지지 않는다. 모바일은 Signal 상세 응답만으로 화면을 렌더하고
body 없는 convert action을 호출할 수 있다.

worker와 운영 schema에는 `signal_evidence` 의미 필드 계약만 추가하면 된다. 이 변경이 운영 DB에
반영되기 전에는 새 server mapping을 배포할 수 없다. 민수가 서버 내 모듈로 구현되면 고정 목 draft와
목 suggestion을 민수 호출로 교체하며, 그 시점의 모듈 경계는 후속 ADR로 정한다. outbox 경계와 이벤트
payload는 ID 중심 hydrate 원칙을 유지하므로 ADR-0008·0010의 변경은 필요하지 않다.
