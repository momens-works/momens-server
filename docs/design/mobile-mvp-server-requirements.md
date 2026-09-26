# 모바일 MVP 서버 요구사항 명세

작성일: 2026-07-01

상태: Draft

이 문서는 Momens Mobile MVP를 지원하기 위해 `momens-server`가 제공해야 하는 서버 요구사항을
정리한다. 목적은 구현 상세설계가 아니라, 제품 범위와 서버 책임 경계를 합의 가능한 수준으로
명확히 하는 것이다.

## 배경

Momens Mobile은 기존 웹 기능을 축소 이식하는 앱이 아니다. 모바일 MVP의 중심은 Project Owner가
프로젝트에 영향을 줄 수 있는 중요한 신호를 확인하고, 근거를 검토한 뒤, 실행 가능한 액션으로
닫는 경험이다.

모바일 MVP는 다음 3개 탭을 중심으로 한다.

- 신호
- 브리프
- 프로젝트

## 목표

- Project Owner가 오늘 확인해야 할 신호를 모바일에서 파악할 수 있어야 한다.
- Project Owner가 신호의 근거와 민수 제안을 확인할 수 있어야 한다.
- Project Owner가 신호를 태스크 등록으로 처리할 수 있어야 한다.
- 처리 결과는 Signal의 프로젝트 단위 처리 이력과 프로젝트 맥락에 반영되어야 한다.
- Project Owner가 프로젝트 태스크를 생성하고, 상세를 확인하고, 체크리스트를 토글하고, 기본 정보를
  수정할 수 있어야 한다.
- 태스크 생성 이후 retrieval projection은 api-server가 직접 쓰지 않고 worker가 처리할 수 있어야 한다.
- Signal 발생 시 모바일 사용자가 알 수 있도록 push notification을 보낼 수 있어야 한다.

## Non-goals

모바일 MVP 범위에서 다음은 제외한다.

- 기존 웹 기능 전체 이관
- Linear/Jira 수준의 태스크 보드
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
- 관련자료 상세 전용 API
- task 생성, Signal 삭제(dismiss), task 상태 변경 push notification
- worker 내부 재시도/DLQ 저장 방식 상세설계
- DB 컬럼, index, FK, Java public API, 패키지 구조 등 구현 상세설계

## 용어

| 용어 | 의미 |
| --- | --- |
| Signal / 신호 | 프로젝트 목표, 일정, 범위, 품질, 의사결정, 실행 흐름에 영향을 줄 수 있어 사람이 검토해야 하는 변화 |
| Signal 표시 라벨 | 화면에서 Signal type을 묶어 보여주는 라벨. `risk`/`change`는 `Needs action`, `decision`은 `Needs review`, `question`은 `Needs decision`으로 표현한다. 처리 상태나 목록 필터가 아니다 |
| Evidence / 근거 | 신호 판단을 뒷받침하는 출처 기반 정보 |
| Task draft | 민수가 태스크 등록 시점에 생성하는 사용자 확정 액션용 초안. 최종 task가 아니다 |
| Minsu suggestion | 신호를 바탕으로 민수가 제안하는 다음 행동 |
| Brief / 브리프 | 프로젝트 상태와 최근 맥락을 짧게 요약한 화면 단위 |
| Task / 태스크 | 담당자와 상태를 가지고 진행되는 실행 항목 |
| Decision / 결정 | 근거와 함께 보존되는 프로젝트 선택 |

## 시스템 책임

### api-server

api-server는 사용자 확정 액션의 transactional owner다.

api-server는 다음을 책임진다.

- 모바일 조회 API 제공
- worker가 생성한 Signal 조회와 convert 기본값 제공
- 사용자 확정 액션 처리
- 최종 task 생성
- 프로젝트 단위 Signal 처리 이력 반영
- projection 처리를 위한 outbox 이벤트 발행
- api-server가 발행하는 outbox 이벤트 멱등키 생성
- Signal 발생 push notification 발송(worker가 발행한 `signal.created` outbox를 소비, [ADR-0009](../adr/0009-notification-consumer-ownership.md))

api-server는 다음을 직접 수행하지 않는다.

- Signal 생성/감지
- 풍부한 Minsu suggestion/draft 생성
- `retrieval_documents` 직접 write
- `retrieval_events` 직접 write
- retrieval 서버 직접 호출
- worker 재시도/DLQ 정책 결정

### worker

worker는 Signal 생성과 후속 projection 처리를 책임진다.

worker는 다음을 책임진다.

- 원천 이벤트 기반 Signal 생성
- 풍부한 Minsu suggestion/draft 생성
- api-server가 발행한 outbox 이벤트 소비
- retrieval projection write
- projection write 재시도
- DLQ 또는 실패 이벤트 관리

worker의 내부 소비 상태, 재시도 정책, DLQ 저장 위치는 worker 담당 팀과 후속 확정한다.

### retrieval

retrieval은 `retrieval_events`를 polling하고 indexing한다.

api-server는 retrieval 서버를 직접 호출하지 않는다.

## 공통 API 요구사항

- 모바일 기능 API는 `/api/mobile/*` 경로를 사용해야 한다.
- 모바일 인증은 기존 `/api/auth/*` Bearer 기반 인증 API를 재사용해야 한다.
- 모든 모바일 기능 API는 인증된 사용자만 호출할 수 있어야 한다.
- 모바일 클라이언트는 `Authorization: Bearer {accessToken}` 헤더를 전송해야 한다.
- 모바일 클라이언트는 `API-Version: 1` 헤더를 전송해야 한다.
- 성공 응답에는 공통 wrapper를 두지 않는다.
- 실패 응답은 Standard 에러 응답을 사용한다.
- 응답 필드 이름은 `snake_case`를 사용한다.
- API path와 versioning은 이 저장소의 `docs/spec/api-versioning.md`와 ADR-0006을 따른다.

## 공통 에러 요구사항

모바일 API는 최소 다음 에러 코드를 문서화해야 한다.

| 상황 | HTTP status | Code |
| --- | --- | --- |
| 인증 정보 없음 | 401 | `AUTH_UNAUTHORIZED` |
| 토큰 무효 또는 만료 | 401 | `AUTH_INVALID_TOKEN` |
| 권한 없음 | 403 | `AUTH_FORBIDDEN` |
| 요청 값 검증 실패 | 400 | `COMMON_VALIDATION_FAILED` |
| 프로젝트 없음 | 404 | `PROJECT_NOT_FOUND` |
| 신호 없음 | 404 | `SIGNAL_NOT_FOUND` |
| 태스크 없음 | 404 | `TASK_NOT_FOUND` |
| 태스크 체크리스트 항목 없음 | 404 | `TASK_CHECKLIST_ITEM_NOT_FOUND` |
| 현재 상태에서 수행할 수 없는 신호 액션 | 409 | `SIGNAL_INVALID_STATE` |

Notion 문서에 남아 있는 `AUTH_UNAUTHENTICATED`, `COMMON_VALIDATION` 표기는 이 저장소 기준과
맞지 않으므로 사용하지 않는다.

## 기능 범위와 우선순위

우선순위는 다음 3계층으로 나눈다. `선택`은 모바일 MVP 안에서 억지로 만들지 않고, 로드맵상
MVP 이후 단계(웹/레거시 이관)의 이관 후보로 둔다.

| 우선순위 | 의미 |
| --- | --- |
| MVP | 모바일 MVP의 핵심 사용자 경험에 직접 연결되는 기능. 신호 확인, 근거 확인, 태스크 전환, 브리프, 태스크 흐름을 포함한다. |
| 필수 | 모바일 MVP를 실제 앱에서 사용하기 위해 반드시 필요한 API/계약/인증/에러 처리. 모바일 API 전체와 계약 안정화를 포함한다. |
| 선택 | 모바일 MVP 이후 단계의 이관 후보. 기존 웹 API 전체 이관, 레거시 기능 확장, 레거시 서버 폐기 준비는 여기에 둔다. |

| 영역 | 기능 | 우선순위 | 1차 범위 | 비고 |
| --- | --- | --- | --- | --- |
| Mobile Signal | Signal 목록/상세 조회, convert-to-task, dismiss | MVP | 포함 | 최신 Figma 기준 핵심 흐름 |
| Mobile Brief | 프로젝트 브리프 조회 | MVP | 포함 | 프로젝트 상태와 최근 맥락 요약 탭 |
| Mobile Task | 태스크 보드/생성/상세/수정/체크리스트 토글 | MVP | 포함 | 프로젝트 탭 핵심 흐름 |
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

서버는 로그인 후 모바일 앱 진입에 필요한 기본 컨텍스트를 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/bootstrap`
- 응답은 내 사용자 정보, 기본 project id, 접근 가능한 project 목록을 포함해야 한다.
- 로그인 응답에는 project context를 포함하지 않고 별도 API로 조회한다.
- 기본 project id는 임의의 project 1개면 충분하다고 기획이 확인했고(2026-07-04), 서버는 접근
  가능한 project 중 가장 최근에 만든 것을 선택한다.
- 접근 가능한 project가 없으면 `200`으로 기본 project id는 `null`, project 목록은 빈 배열을
  반환하고, 빈 화면 처리는 앱이 담당한다(2026-07-04 가결정, 기획 확인 후 확정).

### R-READ-001. 신호 목록 조회

서버는 프로젝트 단위 신호 목록을 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/projects/{projectId}/signals`
- 신호 목록은 프로젝트 스코프를 기준으로 한다.
- MVP 목록은 아직 처리되지 않은 Signal만 반환한다.
- 목록은 cursor pagination으로 한 페이지씩 반환합니다. 기본 페이지 크기는 5이고 상한은 50입니다(MOM-0963).
- 처리된 Signal(`convert-to-task` 또는 `dismiss`)을 다시 조회하거나 필터링하는 흐름은 MVP에서 제공하지 않는다.
- 목록 응답은 신호 카드 렌더링에 필요한 정보를 포함해야 한다.
- 목록 응답은 type, title, impact, Minsu suggestion을 표현할 수 있어야 한다.
- project는 요청 경로로 고정되므로 Signal 항목마다 project id를 반복하지 않는다.
- Signal backing의 project id는 목록 필터와 convert-to-task의 task 귀속에 필요하므로 서버 내부에는 유지한다.
- impact는 worker가 Signal과 함께 생산하며, worker가 준비되지 않은 MVP 환경에서는 같은 backing
  계약의 fixture가 채운다.
- Minsu suggestion은 민수 산출물이며, 민수(서버 내 모듈로 구현 예정)가 구현되기 전에는 목으로 처리한다.
- 화면의 `Needs action`, `Needs review`, `Needs decision` 라벨은 Signal type에서 파생한다.

### R-READ-002. 신호 상세 조회

서버는 신호 1건의 상세를 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/signals/{signalId}`
- 상세 응답은 type, title, Signal 전체의 impact, 근거 목록, Minsu suggestion을 표현할 수 있어야 한다.
- project·description·task draft·가능한 action 목록은 모바일 상세 화면에서 사용하지 않으므로 응답하지 않는다.
- 근거는 source 정보와 `대상`, `변화`, `영향`을 포함해야 한다. 세 의미 값은 worker 또는 동일 backing
  계약의 fixture가 생산하고, 각각 공백 포함 30자 이하로 제한한다(화면설계서 기준).
- 근거 행 헤더는 도구 이름으로 표시하므로 원천 문서 제목은 응답에 포함하지 않는다.
- 상대 시간 라벨은 서버가 생성하지 않고 앱이 `occurred_at`으로 렌더한다.
- 처리된 Signal을 다시 보는 inbox가 MVP 이후 범위이므로 상세 조회도 미처리 Signal만 대상으로 한다.
- 상세 화면의 `Needs action` 등 상단 라벨은 처리 상태가 아니라 Signal type 기반 표시 라벨이다.
- 근거는 원천 도구를 모두 열지 않아도 사용자가 판단할 수 있는 형태로 제공되어야 한다.

### R-READ-003. 프로젝트 브리프 조회

서버는 프로젝트 브리프를 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/projects/{projectId}/brief`
- 브리프는 프로젝트 상태와 최근 맥락을 짧게 요약해 보여주는 탭이다(홈 탭은 신호).
- 응답은 프로젝트 스냅샷, 시그널 요약 문단(`signal_summary.summary`), signal summary
  filter/dropdown, 우선순위를 표현할 수 있어야 한다. (2026-07-10 화면설계서 기준으로 종전
  "리뷰 요약"을 시그널 요약 문단으로 정리했다.)
- 시그널 요약의 필터 전환과 더보기는 커서 기반 페이지네이션으로 제공한다(2026-07-10 확정,
  하위 엔드포인트 `GET .../brief/signal-summary`).
- 브리프 signal summary는 당일(Asia/Seoul 기준) 생성된 시그널을 처리 여부와 무관하게 집계한다
  (2026-07-10 확정, MOM-81). 태스크 전환이나 dismiss와 상관없이 그날 온 개수를 유지하고, 소프트
  삭제된 시그널은 제외한다. 시그널 탭(미처리 누적 조회)과는 다른 조회 기준이다.
- 브리프 signal summary는 `change`를 포함해 저장된 모든 type을 노출한다(2026-07-10 기획
  갱신으로 종전 change 제외를 번복). 필터 칩은 당일 시그널에 있는 type으로 데이터 기반 구성한다.

### R-READ-004. 프로젝트 태스크 목록 조회

서버는 프로젝트 태스크 목록을 상태별로 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/projects/{projectId}/tasks`
- 응답은 태스크를 `todo`, `in_progress`, `done` 그룹으로 표현할 수 있어야 한다.
- 태스크 카드에는 제목, role, priority, 관련자료 개수를 표현할 수 있어야 한다.

### R-READ-005. 태스크 상세 조회

서버는 태스크 상세를 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/tasks/{taskId}`
- 응답은 태스크 기본 정보, role, 담당자, 우선순위, 목적, 체크리스트, 관련자료 축약본, 열린 질문,
  다음 액션을 표현할 수 있어야 한다.
- 관련자료 bottom sheet는 태스크 상세 응답의 관련자료 축약본으로 구성할 수 있어야 한다.
- 원본 문서 이동은 관련자료의 `source_url`을 사용한다.
- 기존 데이터에서 합성할 수 없는 값은 MVP 응답 정책에 따라 빈 값으로 표현할 수 있어야 한다.

### R-READ-006. 프로젝트 멤버 조회

서버는 태스크 담당자 선택에 사용할 프로젝트 멤버 목록을 조회할 수 있어야 한다.

- 경로: `GET /api/mobile/projects/{projectId}/members`
- 검색어가 전달되면 이름 기준으로 필터링할 수 있어야 한다.
- 응답은 담당자 선택 bottom sheet에서 사용할 사용자 id, 이름, 프로필 이미지를 표현할 수 있어야 한다.

## 모바일 액션 요구사항

### R-ACTION-001. 신호를 태스크로 전환

서버는 신호를 태스크로 전환할 수 있어야 한다.

- 경로: `POST /api/mobile/signals/{signalId}/actions/convert-to-task`
- 요청 body를 받지 않는 원탭 action이다.
- 태스크 등록 시점에 민수가 draft(title, role, priority)를 생성한다. 비활성·설정 무효·입력
  부족·외부 실패·출력 무효 시 고정 draft(title=15자로 제한한 Signal title, role=`pm`,
  priority=`medium`)를 사용한다.
- task draft title은 공백 포함 최대 15자여야 한다.
- task draft role은 `pm`, `design`, `backend`, `frontend` 중 하나여야 한다.
- task draft priority는 `low`, `medium`, `high` 중 하나여야 한다.
- 서버는 최종 task를 생성해야 한다.
- 서버는 Signal이 처리되었음을 기록하고, 이후 MVP Signal 목록에서 제외해야 한다.
- 서버는 projection 처리를 위한 outbox 이벤트를 발행해야 한다.
- task 생성, Signal 처리 이력 반영, outbox 이벤트 발행은 사용자 관점에서 일관되게 처리되어야 한다.
- 같은 신호에 대한 같은 액션 재시도는 중복 task를 만들지 않아야 한다.
- 이미 처리된 신호에 같은 액션을 재요청하면 `200`으로 기존 task 결과를 멱등하게 반환해야 한다.
- 이미 다른 action으로 처리된 신호에 요청하면 `SIGNAL_INVALID_STATE`(409)를 반환한다.

### R-ACTION-002. 신호 삭제(dismiss)

서버는 사용자가 제안된 Signal을 MVP 흐름에서 수용하지 않겠다고 표시할 수 있어야 한다.

- 경로: `POST /api/mobile/signals/{signalId}/actions/dismiss`
- 모바일 화면의 버튼 라벨은 `삭제`지만, 서버 action 이름은 `dismiss`다.
- 서버는 Signal이 처리되었음을 기록하고, 이후 MVP Signal 목록에서 제외해야 한다.
- dismiss는 물리 삭제가 아니며 Signal 자체가 잘못됐다고 확정하는 것도 아니다. 현재 사용자가 이 Signal을
  task로 전환하지 않고 MVP 목록에서 삭제 처리하는 액션이다.
- 같은 신호에 대한 같은 dismiss 재시도는 멱등하게 `200`으로 처리한다.
- 이미 다른 action으로 처리된 신호에 요청하면 `SIGNAL_INVALID_STATE`(409)를 반환한다.
- 삭제 처리한 Signal을 다시 보는 inbox 흐름은 MVP 이후 요구사항이다.

### R-ACTION-003. 일반 태스크 생성

서버는 태스크 탭의 플로팅 버튼에서 일반 태스크를 생성할 수 있어야 한다.

- 경로: `POST /api/mobile/projects/{projectId}/tasks`
- MVP 생성 범위는 `title`, `role`, `priority`다.
- 담당자, 설명, 체크리스트는 1차 생성 요청에 포함하지 않는다.
- 생성된 task는 기본적으로 `todo` 그룹에 표시되어야 한다.
- Signal에서 task를 생성하는 `convert-to-task`와 구분해야 한다.

### R-ACTION-004. 태스크 수정

서버는 태스크 상세 상단 연필 아이콘에서 태스크를 수정할 수 있어야 한다.

- 경로: `PATCH /api/mobile/tasks/{taskId}`
- MVP 수정 범위는 제목, role, 담당자, 우선순위, 목적, 완료기준이다.
- 보내지 않은 필드는 변경하지 않아야 한다.
- 담당자 제거는 `assignee_id: null`로 표현한다.
- 완료기준 수정은 기존 항목 제목 수정, 새 항목 추가, 기존 항목 삭제를 처리할 수 있어야 한다.

### R-ACTION-005. 태스크 체크리스트 토글

서버는 태스크 상세의 체크박스 완료 상태를 변경할 수 있어야 한다.

- 경로: `PATCH /api/mobile/tasks/{taskId}/checklist-items/{itemId}`
- `itemId`는 `taskId`에 속한 항목이어야 한다.
- 완료 수와 전체 수를 함께 반환해 상세 화면 카운트를 즉시 갱신할 수 있어야 한다.

## 합성/파생 필드 응답 정책

모바일 MVP 응답에는 기존 레거시 테이블에서 직접 조회되는 필드와, 여러 데이터를 조합해 만드는
파생 필드가 함께 포함된다.

| 필드 | Source | 값이 없을 때 |
| --- | --- | --- |
| `materials[]` | `source_refs` + `entity_relations`(task ↔ source_ref) | `[]` |
| `material_count` | `materials[]` 개수 | `0` |
| `open_questions[]` | `task_open_questions`. Minsu 산출물이고 MVP는 fixture가 채운다 | `[]` |
| `next_action` | `tasks.next_action`. Minsu 산출물이고 MVP는 fixture가 채운다 | `null` |
| `signal_summary.summary` | `signal_digests`. Minsu 산출물이고 MVP는 fixture가 채운다 | `null` |

서버는 backing source가 없는 값을 임의 생성하지 않는다. worker가 준비되지 않은 MVP 환경에서는
production 코드 분기 대신 worker와 같은 backing 계약의 fixture를 사용한다. 민수 산출물 중 아직
구현되지 않은 Minsu suggestion을 목으로 처리하는 것은 이 원칙의 명시적 예외다(ADR-0011).

## Signal 요구사항

- Signal은 모바일 API가 안정적으로 조회하고 액션 대상으로 참조할 수 있는 형태로 저장되어야 한다.
- Signal 생성은 worker가 담당해야 한다.
- Minsu suggestion과 task draft는 민수 산출물이다. task draft는 서버 내 `minsu` 모듈이
  생성한다(ADR-0014). Minsu suggestion은 아직 구현 전이라 목으로 처리한다.
- Signal type은 `risk`, `decision`, `change`, `question`을 지원해야 한다.
- Signal은 사용자가 어떤 검토를 해야 하는지 표현할 수 있어야 한다.
- Signal description은 backing에 보존할 수 있지만 모바일 상세 응답에는 노출하지 않는다.
- 화면 표시 라벨은 Signal type으로 정한다. `risk`/`change`는 `Needs action`, `decision`은
  `Needs review`, `question`은 `Needs decision`이다.
- 화면설계서의 카테고리 표시도 Signal type으로 정한다. `risk`는 `Risk`, `decision`은 `Decision`,
  `change`는 `Change`, `question`은 `Question`으로 표시한다.
- Signal 표시 라벨은 처리 상태가 아니며, MVP 목록 필터로 제공하지 않는다.
- Signal은 프로젝트 단위 처리 이력을 가질 수 있어야 한다.
- Signal 처리 여부는 사용자별이 아니라 프로젝트 단위로 반영되어야 한다.
- 모바일 MVP의 Signal 목록은 처리되지 않은 Signal만 반환하며, 처리된 Signal을 다시 보는 흐름은 제공하지 않는다.
- MVP에서 Signal을 처리하는 action은 `convert-to-task`와 `dismiss`다. 화면의 `삭제` 버튼은 `dismiss` action으로
  처리한다.
- Signal 상세는 근거 목록을 표현할 수 있어야 하며, 개수별 펼침·접힘·빈 상태는 모바일이 담당한다.
- Signal backing에는 task draft를 저장하지 않는다. draft는 태스크 등록 시점에 생성한다.

## Task draft 요구사항

- Task draft는 최종 task가 아니라 사용자 원탭 action을 위한 초안이다.
- draft(title, role, priority)는 민수 산출물이며, Signal 생성 시점이 아니라 태스크 등록
  (`convert-to-task`) 시점에 생성한다. Signal backing에는 저장하지 않는다.
- 민수는 서버 내 `minsu` 모듈의 `SignalTaskDraftGenerator`로 구현한다. 비활성·설정 무효·입력
  부족·외부 실패·출력 무효 시 api-server가 고정 draft(title=15자로 제한한 Signal title,
  role=`pm`, priority=`medium`)를 사용한다.
- draft 생성에 사용하는 evidence는 기존 정렬의 의미 있는 앞선 10건으로 제한한다. 이 제한은
  Signal evidence 저장과 상세 API 응답에는 적용하지 않는다.
- api-server는 draft를 모바일 상세 응답에 노출하지 않고 body 없는 `convert-to-task`의 입력으로만
  사용한다.
- 사용자의 확정 action이 있기 전까지 task draft는 프로젝트의 실행 항목으로 간주하지 않는다.

생산 책임은 [ADR-0011](../adr/0011-signal-evidence-and-task-draft-contract.md), 모듈·LLM·fallback
경계는 [ADR-0014](../adr/0014-minsu-task-draft-module-and-llm-boundary.md)와
[MOM-0803 상세 설계](minsu-signal-task-draft-design.md)를 따른다.

## Outbox 요구사항

- api-server는 projection write를 직접 수행하지 않고 outbox 이벤트를 발행해야 한다.
- outbox 이벤트는 task 생성 같은 사용자 확정 액션의 결과를 worker가 처리할 수 있게 해야 한다.
- api-server는 자신이 발행하는 outbox 이벤트의 멱등키를 서버에서 생성해야 한다.
- 클라이언트는 모바일 MVP에서 `Idempotency-Key` 헤더를 보낼 필요가 없다.
- 멱등키는 같은 Signal에 대한 같은 액션이 중복 처리되지 않도록 생성되어야 한다.
- MVP에서 필요한 이벤트는 최소 다음 4개다.
  - `signal.created`: Signal 발생. issued_by=worker, `aggregate_type=signal`, `aggregate_id=signal_id`,
    payload `{}`.
  - `task.created`: task 생성. issued_by=api-server, `aggregate_type=task`, `aggregate_id=task_id`, payload
    `{ "origin_type": "manual|signal", "origin_signal_id": null|"<signal_id>" }`.
  - `signal.converted_to_task`: Signal을 task로 수용. issued_by=api-server, `aggregate_type=signal`,
    `aggregate_id=signal_id`, payload `{ "task_id": "<task_id>" }`.
  - `signal.dismissed`: Signal 삭제(dismiss). issued_by=api-server, `aggregate_type=signal`, `aggregate_id=signal_id`,
    payload `{}`.
- worker의 소비 상태, 재시도, DLQ 저장 방식은 이 문서에서 확정하지 않는다.

## Push notification 요구사항

- MVP에서 push notification은 Signal 발생 알림까지만 포함한다.
- task 생성, Signal 삭제(dismiss), task 상태 변경에 대한 push notification은 MVP 이후 요구사항이다.
- Signal 발생 알림은 worker가 Signal backing을 생성한 뒤 사용할 수 있는 이벤트를 기준으로 한다.
- Signal 발생 알림의 표시 문구와 data payload는
  [MOM-0689 Signal push 데모 설계](signal-push-demo-design.md)를 따른다. FCM data payload에는 Signal type을
  포함하지 않고, 앱은 상세 조회 후 type을 사용한다.
- FCM 디바이스 토큰 등록/해제 HTTP API 계약은
  [MOM-0689 Signal push 데모 설계](signal-push-demo-design.md)를 따른다.
- 모바일 앱에서 이미 처리된 Signal을 다시 보는 inbox 흐름은 MVP 이후 요구사항이다.

## 권한 요구사항

- 모든 모바일 기능 API는 인증된 사용자만 접근할 수 있어야 한다.
- 프로젝트 리소스 접근은 프로젝트 또는 워크스페이스 멤버십/RBAC 기준으로 제한되어야 한다.
- 인증/인가 실패 응답은 이 저장소의 Standard 에러 응답 규격을 따라야 한다.
- 프로젝트 단위 role(`bootstrap`의 `projects[].role`, 멤버 조회 응답)은 별도 project 멤버 테이블 없이
  소속 workspace 멤버십 role로 매핑한다.

## 보류 항목

다음 항목은 요구사항 명세 단계에서 확정하지 않는다.

- `Keep Watching` 액션의 사용자 플로우와 API 포함 여부
- `record-decision`, `resolve`, `snooze`의 후속 모바일 사용자 플로우와 API 포함 여부
- Signal 처리 이력의 내부 저장 구조
- Signal 저장 테이블의 상세 스키마(이 문서는 제품/API 요구사항만 다루며, DB 상세 스키마는 별도 설계 산출물에서 관리한다)
- Signal evidence 저장 테이블의 상세 스키마(이 문서는 제품/API 요구사항만 다루며, DB 상세 스키마는 별도 설계 산출물에서 관리한다)
- Outbox 테이블의 상세 스키마(이 문서는 제품/API 요구사항만 다루며, DB 상세 스키마는 별도 설계 산출물에서 관리한다)
- worker outbox 소비 상태, 재시도, DLQ 설계
- FCM 디바이스 토큰 등록/해제 API 계약(경로, 요청 body, 토큰 회전/중복/폐기 정책)
- Minsu suggestion 목 문구의 구체 값과 suggestion 생성 경계(task draft 경계는 ADR-0014에서 확정)
- 태스크 상세 확장 필드가 비어 있을 때의 구체 응답 값 정책
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

- Notion 일부 문서에는 `/me`를 새 서버로 라우팅한다고 남아 있으나, 현재 저장소의 ADR-0006 기준은
  `/me` alias 폐기와 `/api` 단일화다. 이 문서는 저장소 기준을 따른다.
- Notion 일부 문서에는 모바일 Signal 액션 API가 여러 개 남아 있으나, 최신 MVP Signal 액션은
  `convert-to-task`와 `dismiss`다. `record-decision`, `resolve`, `snooze`, `keep-watching`은 MVP에서
  제외한다.
- Notion 일부 문서에는 projection write를 api-server 트랜잭션에 포함하는 표현이 있으나, 최신 합의는
  api-server가 outbox 이벤트를 발행하고 worker가 projection write를 담당하는 구조다.

## Proto 정합성 메모

- `momens-proto`에는 현재 Signal 전용 proto message/service가 없다. 내부 서버 간 도메인 계약 정의가
  필요하면 proto 정의를 두고, PR 단위로 `@server` 리뷰 후 관련 서버가 최신화한다.
- `momens.retrieval.v1.RetrievalEvent`는 worker가 publish하고 retrieval이 polling하는 dedicated
  read-model event로 정의되어 있다. api-server가 projection write를 직접 하지 않고 outbox를 통해
  worker에 넘기는 최신 책임 경계와 충돌하지 않는다.
- 현재 `RetrievalDocumentType`과 `RetrievalEventType`에는 task 전용 값은 있지만 decision 전용 값은
  없다. decision 기록을 retrieval에 반영할 때 기존 `MemoryType.DECISION` / memory document 흐름으로
  표현할지, decision 전용 retrieval proto 값을 추가할지는 후속 설계에서 확정해야 한다.
- 제품 문서에는 Analytics, CRM, API 출처가 등장하지만 현재 `SourceType` proto enum에는 Slack, Notion,
  Linear, Figma, GitHub, Momens task/internal 중심 값만 있다. Analytics/CRM/API를 typed source로
  노출하려면 proto enum 확장 또는 기존 source type으로의 매핑 정책이 필요하다.
