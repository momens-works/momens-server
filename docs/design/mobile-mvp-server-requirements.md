# 모바일 MVP 서버 요구사항 명세

작성일: 2026-07-01

상태: Draft

이 문서는 Momens Mobile MVP를 지원하기 위해 `momens-server`가 제공해야 하는 서버 요구사항을
정리한다. 목적은 구현 상세설계가 아니라, 제품 범위와 서버 책임 경계를 합의 가능한 수준으로
명확히 하는 것이다.

## 배경

Momens Mobile은 기존 웹 기능을 축소 이식하는 앱이 아니다. 모바일 MVP의 중심은 Project Owner가
프로젝트에 영향을 줄 수 있는 중요한 시그널을 확인하고, 근거를 검토한 뒤, 실행 가능한 액션으로
닫는 경험이다.

모바일 MVP는 다음 3개 탭을 중심으로 한다.

- 시그널
- 브리프
- 프로젝트

## 목표

- Project Owner가 오늘 확인해야 할 시그널을 모바일에서 파악할 수 있어야 한다.
- Project Owner가 시그널의 근거와 민수 제안을 확인할 수 있어야 한다.
- Project Owner가 시그널을 작업 등록으로 처리할 수 있어야 한다.
- 처리 결과는 Signal의 프로젝트 단위 처리 이력과 프로젝트 맥락에 반영되어야 한다.
- Project Owner가 프로젝트 작업을 생성하고, 상세를 확인하고, 완료 기준을 토글하고, 기본 정보를
  수정할 수 있어야 한다.
- 작업 생성 이후 retrieval projection은 api-server가 직접 쓰지 않고 worker가 처리할 수 있어야 한다.
- Signal 발생 시 모바일 사용자가 알 수 있도록 push notification을 보낼 수 있어야 한다.

## Non-goals

모바일 MVP 범위에서 다음은 제외한다.

- 기존 웹 기능 전체 이관
- Linear/Jira 수준의 작업 보드
- Notion 수준의 문서 편집
- 모바일에서 소스 연결과 권한 설정을 완결하는 기능
- 범용 민수 AI 챗봇
- 모든 원천 도구 알림을 그대로 모은 활동 피드
- Share
- Ask Owner
- Signal 발생 외 push notification
- Keep Watching
- Signal record-decision 액션
- Signal resolve 액션
- Signal snooze 액션
- 연결된 소스 상세 전용 API
- task 생성, Signal 삭제(dismiss), task 상태 변경 push notification
- worker 내부 재시도/DLQ 저장 방식 상세설계
- DB 컬럼, index, FK, Java public API, 패키지 구조 등 구현 상세설계

## 용어

제품 용어의 출처와 작업별 적용 기준은 [문서 인덱스](../README.md#제품-레벨-문서는-어디에)를 따른다.
모바일 표시 라벨과 서버 값의 차이는 [모바일 API 명세](../spec/mobile-api.md#enum)에서 확인한다.

## 시스템 책임

- api-server는 모바일 조회와 사용자 확정 액션을 처리하며 task·Signal 처리 이력·outbox를
  원자적으로 저장한다. task draft는 서버 내 `minsu`가 생성한다
  ([ADR-0014](../adr/0014-minsu-task-draft-module-and-llm-boundary.md),
  [ADR-0015](../adr/0015-minsu-async-task-draft-generation.md)).
- worker는 Signal 생성과 outbox 소비 후 retrieval projection 쓰기를 담당한다. api-server는
  retrieval 저장소를 직접 쓰거나 retrieval 서버를 직접 호출하지 않는다
  ([ADR-0008](../adr/0008-outbox-worker-projection-boundary.md)).
- retrieval은 `retrieval_events`를 polling하고 indexing한다.
- Signal push는 api-server가 `signal.created`를 소비해 발송한다
  ([ADR-0009](../adr/0009-notification-consumer-ownership.md)).
- Minsu suggestion은 아직 목이며 생성 경계는 보류 상태다. task draft와 혼동하지 않는다.
  worker 내부 소비 상태·재시도·DLQ 정책은 worker 소관으로 후속 확정한다.

## 공통 API 요구사항

모바일 인증·경로·시각·필드 표기는 [모바일 API 공통 계약](../spec/mobile-api.md#공통),
버전 전달은 [API 버저닝](../spec/api-versioning.md)을 따른다. 모바일 기능 API는 인증이 필요하되
로그인 전 앱 업데이트 확인은 공개 API다.

## 공통 에러 요구사항

모바일은 [Standard 에러 응답](../spec/api-response-error-codes.md)을 사용한다.
엔드포인트별 오류와 입력 형식은 [OpenAPI 스냅샷](../spec/openapi.json)에서 확인한다.

## 기능 범위와 우선순위

우선순위는 다음 3계층으로 나눈다. `선택`은 모바일 MVP 안에서 억지로 만들지 않고, 로드맵상
MVP 이후 단계(웹/레거시 이관)의 이관 후보로 둔다.

| 우선순위 | 의미 |
| --- | --- |
| MVP | 모바일 MVP의 핵심 사용자 경험에 직접 연결되는 기능. 시그널 확인, 근거 확인, 작업 전환, 브리프, 작업 흐름을 포함한다. |
| 필수 | 모바일 MVP를 실제 앱에서 사용하기 위해 반드시 필요한 API/계약/인증/에러 처리. 모바일 API 전체와 계약 안정화를 포함한다. |
| 선택 | 모바일 MVP 이후 단계의 이관 후보. 기존 웹 API 전체 이관, 레거시 기능 확장, 레거시 서버 폐기 준비는 여기에 둔다. |

| 영역 | 기능 | 우선순위 | 1차 범위 | 비고 |
| --- | --- | --- | --- | --- |
| Mobile Signal | Signal 목록/상세 조회, convert-to-task, dismiss | MVP | 포함 | 최신 Figma 기준 핵심 흐름 |
| Mobile Brief | 프로젝트 브리프 조회 | MVP | 포함 | 프로젝트 상태와 최근 맥락 요약 탭 |
| Mobile Task | 작업 보드/생성/상세/수정/완료 기준 토글 | MVP | 포함 | 프로젝트 탭 핵심 흐름 |
| Mobile API Contract | `/api/mobile/*`, API-Version, 표준 에러, snake_case, no wrapper | 필수 | 포함 | 모바일팀 병렬 개발을 위한 계약 |
| Auth / Bootstrap | 기존 인증 API 재사용, 모바일 bootstrap | 필수 | 포함 | 앱 진입 필수 |
| Projection / Outbox | Signal action 이후 worker projection/후속 처리 연계 | 필수 | 포함 | api-server 직접 retrieval write 금지 |
| Notification | Signal 발생 push notification (api-server가 `signal.created` 소비, ADR-0009) | 필수 | 포함 | task 생성/Signal 삭제/task 상태 변경 알림은 MVP 이후 |
| Web API Migration | workspace/project/milestone/task/decision/blocker 등 기존 웹 API 이관 | 선택 | 제외 | 로드맵 단계 C |
| Legacy Retirement | 레거시 서버 폐기, DB schema ownership 이전 | 선택 | 제외 | 로드맵 단계 D |
| 후속 Signal 흐름 | 처리된 Signal inbox, record-decision, resolve, snooze, keep-watching | 선택 | 제외 | 삭제 처리한 Signal 다시 보기와 후속 액션은 MVP 이후 |

단계 C·D의 실행 단위, 계약 보존, writer·projection 전환과 종료 조건은
[레거시 Product API 이관 전략](legacy-product-api-migration/strategy.md)에서 관리한다. prod 스키마,
필수 설정, 수기 운영 의무는 로직 이관의 일률적인 선행 단계가 아니라 각 배포와 트래픽 전환이 요구하는
prod gate로 별도 추적한다.

## 모바일 조회 요구사항

### R-READ-000. 모바일 부트스트랩 조회

로그인 후 사용자 정보와 접근 가능한 프로젝트를 별도로 조회한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#모바일-진입)를 따른다.

### R-READ-001. 시그널 목록 조회

프로젝트의 미처리 시그널을 페이지 단위로 조회한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#시그널)를 따른다.

### R-READ-002. 시그널 상세 조회

원천 도구를 모두 열지 않아도 근거를 보고 판단할 수 있어야 한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#시그널)를 따른다.

### R-READ-003. 프로젝트 브리프 조회

프로젝트 상태와 당일 시그널 맥락을 요약한다. 홈 탭은 시그널이다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#브리프)를 따른다.

### R-READ-004. 프로젝트 작업 목록 조회

상태별 작업 보드와 빈 그룹을 제공한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#작업)를 따른다.

### R-READ-005. 작업 상세 조회

작업 정보·완료 기준·연결된 소스를 조회하고 원본으로 이동할 수 있어야 한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#작업)를 따른다.

### R-READ-006. 프로젝트 멤버 조회

작업 담당자 선택에 사용할 멤버를 이름으로 검색한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#프로젝트)를 따른다.

## 모바일 액션 요구사항

### R-ACTION-001. 시그널을 작업으로 전환

body 없는 원탭 액션으로 작업을 등록한다. 재시도로 중복 작업을 만들지 않는다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#시그널)를 따른다.

### R-ACTION-002. 시그널 삭제(dismiss)

시그널을 작업으로 전환하지 않겠다는 처리 기록을 남긴다. 물리 삭제가 아니다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#시그널)를 따른다.

### R-ACTION-003. 일반 작업 생성

프로젝트 탭에서 일반 작업을 생성한다. Signal 전환과 별도 흐름이다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#작업)를 따른다.

### R-ACTION-004. 작업 수정

편집 가능한 필드 전체를 저장하며 서버 관리 필드는 유지한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#작업)를 따른다.

### R-ACTION-005. 작업 완료 기준 토글

작업에 속한 완료 기준 항목의 완료 상태를 바꾸고 전체 카운트를 반환한다.
호출·응답과 세부 동작은 [모바일 API 명세](../spec/mobile-api.md#작업)를 따른다.

## 합성/파생 필드 응답 정책

서버는 backing source가 없는 값을 임의 생성하지 않는다. 생산 기능이 준비되지 않았으면
production 분기 대신 같은 backing 계약의 fixture를 사용한다. Minsu suggestion 목은
[ADR-0011](../adr/0011-signal-evidence-and-task-draft-contract.md)의 명시적 예외다.
필드별 빈 값과 표시 의미는 [모바일 작업 조회](../spec/mobile-api.md#작업)와
[브리프](../spec/mobile-api.md#브리프)에서 관리한다.

## Signal 요구사항

Signal은 worker가 생성하고 사용자별이 아닌 프로젝트 단위 처리 이력을 가진다.
미처리 조회·표시 라벨·근거·전환·dismiss 계약은 [모바일 API](../spec/mobile-api.md#시그널),
저장 구조는 [모듈 맵](module-map.md#signal)을 따른다.

## Task draft 요구사항

task draft는 사용자의 작업 등록 요청으로 준비하며 Signal backing에는 저장하지 않는다.
생성 경계·fallback·evidence 입력 제한은
[ADR-0014](../adr/0014-minsu-task-draft-module-and-llm-boundary.md)와
[Minsu 동기 설계](minsu-signal-task-draft-design.md), 비동기 원장·반영 조건은
[ADR-0015](../adr/0015-minsu-async-task-draft-generation.md)와
[Minsu 비동기 설계](minsu-async-task-draft-design.md)를 따른다.
앱이 관찰하는 `draft_status`와 재조회 계약은 [모바일 API](../spec/mobile-api.md#시그널)에서 관리한다.

## Outbox 요구사항

사용자 확정 액션과 outbox 발행은 같은 트랜잭션에 속한다. 멱등키는 서버가 생성하며
모바일 클라이언트에 `Idempotency-Key`를 요구하지 않는다.
MVP의 기본 이벤트 계약은 다음과 같다. `aggregate_id`는 해당 Signal 또는 task의 ID다.

| event_type | issued_by | aggregate_type | payload |
| --- | --- | --- | --- |
| `signal.created` | worker | signal | `{}` |
| `task.created` | api-server | task | `{ "origin_type": "manual\|signal", "origin_signal_id": null\|"<signal_id>" }` |
| `signal.converted_to_task` | api-server | signal | `{ "task_id": "<task_id>" }` |
| `signal.dismissed` | api-server | signal | `{}` |

공통 envelope·멱등키와 task 수정·삭제 이벤트는 [모듈 맵의 outbox 절](module-map.md#outbox),
비동기 draft 반영 이벤트는 [비동기 설계](minsu-async-task-draft-design.md)를 따른다.
worker 소비 상태·재시도·DLQ 저장 방식은 이 문서에서 확정하지 않는다.

## Push notification 요구사항

MVP는 Signal 발생 알림만 포함한다. 설치 등록·해제와 FCM payload는
[모바일 API](../spec/mobile-api.md#push-설치), 토큰 갱신·중복·폐기 운영 정책은
[Signal push 설계](signal-push-demo-design.md)를 따른다.

## 권한 요구사항

- 모바일 기능 API는 인증된 사용자만 접근할 수 있어야 한다. 단, 로그인 전에 호출하는
  `GET /api/mobile/app-update`는 인증 없이 접근할 수 있는 공개 API다.
- 프로젝트 리소스 접근은 프로젝트 또는 워크스페이스 멤버십/RBAC 기준으로 제한되어야 한다.
- 인증/인가 실패 응답은 이 저장소의 Standard 에러 응답 규격을 따라야 한다.
- 프로젝트 단위 role(`bootstrap`의 `projects[].role`, 멤버 조회 응답)은 별도 project 멤버 테이블 없이
  소속 workspace 멤버십 role로 매핑한다.

## 별도 문서에서 관리하는 계약

HTTP 형식은 [OpenAPI 스냅샷](../spec/openapi.json), 앱 연동 의미는
[모바일 API](../spec/mobile-api.md), 저장 구조는 [모듈 맵](module-map.md)을 따른다.
위 각 절은 해당 정본으로 연결하며 필드 목록을 다시 관리하지 않는다.

## 보류 항목

다음 제품 요구사항은 별도 결정이 필요하다.

- `Keep Watching` 액션의 사용자 플로우와 API 포함 여부
- `record-decision`, `resolve`, `snooze`의 후속 모바일 사용자 플로우와 API 포함 여부
- Minsu suggestion 목 문구의 구체 값과 suggestion 생성 경계(task draft 경계는 ADR-0014에서 확정)
- bootstrap의 project 0개 응답 정책의 확정(R-READ-000의 2026-07-04 가결정안으로 구현했고, 기획
  확인 후 확정한다. 기본 project 선정 규칙은 2026-07-04에 기획이 임의 1개로 확인해 줬다)
- 모바일 화면 재현용 테스트 데이터 세트
- 워크스페이스 전환 진입점의 위치 및 화면 구성

  - ADR-0020에서는 서버 계약만 정의하며, 구체적인 화면 설계는 기획에서 확정한다.
- 푸시 알림의 수신 범위를 워크스페이스 전체 구성원에서 프로젝트 단위로 좁힐지 여부

  - ADR-0020의 결과 절을 바탕으로 별도로 결정한다.
- 워크스페이스 id를 입력받는 조회 API의 도입 시점

  - ADR-0020의 결정 2를 기준으로 추후 검토한다.

## 문서 정합성 메모

외부 문서와 비교한 과거 정합성 검토는 MOM-1053의 이력 보존 댓글에 이관했다.
현행 경로·버전은 [API 버저닝](../spec/api-versioning.md), Signal 액션은
[모바일 API](../spec/mobile-api.md#시그널), projection 책임은
[ADR-0008](../adr/0008-outbox-worker-projection-boundary.md)을 따른다.

## Proto 정합성 메모

- `momens-proto`에는 `Signal`·`SignalEvidence` 메시지가 존재하지만, 현재 `momens-server`는
  생성된 Java 클래스를 사용하지 않는다. 계약의 존재와 서버의 빌드 의존성 사용 여부는 구분한다.
  내부 서버 간 도메인 계약을 추가·변경할 때는 PR 단위로 `@server` 리뷰 후 관련 서버가 최신화한다.
- `momens.retrieval.v1.RetrievalEvent`는 worker가 publish하고 retrieval이 polling하는 dedicated
  read-model event로 정의되어 있다. api-server가 projection write를 직접 하지 않고 outbox를 통해
  worker에 넘기는 최신 책임 경계와 충돌하지 않는다.
- 현재 `RetrievalDocumentType`과 `RetrievalEventType`에는 task 전용 값은 있지만 decision 전용 값은
  없다. decision 기록을 retrieval에 반영할 때 기존 `MemoryType.DECISION` / memory document 흐름으로
  표현할지, decision 전용 retrieval proto 값을 추가할지는 후속 설계에서 확정해야 한다.
- 제품 문서에는 Analytics, CRM, API 출처가 등장하지만 현재 `SourceType` proto enum에는 Slack, Notion,
  Linear, Figma, GitHub, Momens task/internal 중심 값만 있다. Analytics/CRM/API를 typed source로
  노출하려면 proto enum 확장 또는 기존 source type으로의 매핑 정책이 필요하다.
