# MCP 쓰기 도구

`/api/mcp`의 쓰기 도구가 제공하는 권한·수정·재시도 계약이다. 입력 필드와 필수 여부는
[도구 schema](../../modules/mcp/src/main/resources/mcp/write-tools.json)를 따른다.
이관 배경과 구현·검증 이력은 MOM-0993과 [PR #281](https://github.com/momens-works/momens-server/pull/281)에 있다.

## 범위와 권한

- `create_task`, `update_task`, `create_comment`: `mcp:tasks:write`
- `create_milestone`, `update_milestone`, `delete_milestone`: `mcp:milestones:write`

`tools/list`는 읽기·쓰기 도구를 함께 이름 오름차순으로 반환하고 token scope로 필터한다.
실행 시 활성 grant의 user·client·workspace·scope와 현재 membership을 다시 검사한다.
참조 해석에 필요한 조회는 해당 쓰기 scope로 수행하며 별도 읽기 scope를 요구하지 않는다.

프로젝트는 workspace 안의 UUID·PRJ-label·이름으로, 작업은 workspace 안의 UUID·MOM-label로
찾는다. 마일스톤 수정·삭제는 workspace 내 UUID·이름, 작업에 연결할 마일스톤은 해당 프로젝트
내 UUID·이름으로 찾는다. 이름은 대소문자를 구분하지 않는 정확 일치, 유일한 부분 일치 순이며
중복이면 오류다. 삭제된 프로젝트의 작업·마일스톤은 변경하지 않는다.
담당자는 `me` 또는 workspace 멤버 UUID·이메일·이름으로 해석한다.

프로젝트 쓰기 도구와 작업 삭제 도구는 제공하지 않는다. MCP는 도메인 공개 writer를 사용한다.

## 입력과 응답

- 필수 문자열은 공백 제거 후 비어 있으면 오류다. 알 수 없는 필드, 잘못된 타입과 명시적 `null`은 오류다.
- 작업 생성 기본값은 `backlog`·`medium`이다. MCP는 레거시의 대소문자 정규화와
  `progress`/`in-progress` → `in_progress`, `med` → `medium`을 유지한다.
- 날짜는 실제 존재하는 `YYYY-MM-DD`, 마일스톤 `progress`는 0~100 정수다.
- 생성 응답은 기존 `Created …`, 수정은 `Updated …`, 댓글은 `Added a comment to …`,
  마일스톤 삭제는 `Removed milestone …` 텍스트 형식을 유지한다.
- 결과는 `resultType: complete`, `content: [{type: text, text: …}]`다.
  `outputSchema`·`structuredContent`는 제공하지 않는다.
- `create_task`·`update_task` 성공 텍스트에는 `url: <절대 URL>` 행이 추가된다.
  링크 형식과 설정은 [작업 상세 링크](mcp-task-list.md#작업-상세-링크)를 따른다.
- 예상 입력·권한·리소스 오류는 `isError: true`다. 미등록 도구·객체가 아닌 arguments는
  JSON-RPC `-32602`, 예상치 못한 내부 오류는 상세 없는 `-32603 Internal error`다.
  도메인 예외의 원문·details를 tool text로 전달하지 않는다.

### 생략과 삭제

| 수정 필드 | 생략 | 빈 문자열 | 해제 키워드 |
| --- | --- | --- | --- |
| 작업 `description`, `due_date` | 유지 | 삭제 | 없음 |
| 작업 `assignee` | 유지 | 유지 | `none`, `unassign`, `unassigned` |
| 작업 `milestone` | 유지 | 유지 | `none`, `remove`, `unassign`, `unassigned` |
| 작업 `title`, `status`, `priority` | 유지 | 유지 | 없음 |
| 마일스톤 문자열·목표 날짜 | 유지 | 유지 | 없음 |

작업 설명·기한의 빈 문자열 삭제는 기존 Go MCP의 빈 문자열 무시와 다른 계약이다.
마일스톤 설명·요약·목표 날짜 삭제는 MOM-1007에서 공통 command 계약을 확정한 뒤 연결한다.

## 재시도와 트랜잭션

- **생성은 멱등하지 않다.** `create_task`, `create_milestone`, `create_comment`는 동일한 입력을
  다시 호출해도 새 항목을 만든다. 성공 후 응답이 유실되면 재시도로 중복될 수 있다.
  JSON-RPC `id`를 중복 방지 키로 사용하지 않는다.
- 수정은 전달한 필드를 공통 writer에 적용한다. 같은 값이나 변경 필드 없는 요청도 기존과 같은
  `Updated …` 텍스트를 반환한다. 현재 JPA writer는 실제 값이 변하지 않으면 `updated_at`을
  갱신하지 않는다. MCP가 시간을 강제로 갱신하거나 별도 이벤트를 발행하지 않는다. 공통
  `TaskWriter`는 검색 대상 필드가 실제로 바뀌면 `task.updated`를 같은 트랜잭션에 남긴다.
- 삭제 재시도는 이미 삭제된 마일스톤을 찾을 수 없다는 도구 오류다. 추가 삭제 효과는 없다.
- 쓰기 호출은 인가·참조 해석·공통 writer 호출·응답 텍스트 구성까지 하나의 DB 트랜잭션으로 묶는다.
  도메인 검증이나 응답 구성 중 실패하면 rollback 후 오류를 반환한다.
  commit 이후 네트워크 응답 유실에 대한 중복 방지 보장은 없다.

## 운영 전제와 후속 작업

코드 구현·로컬 테스트만으로 운영 전환 조건을 충족하지 않는다.

- MOM-0898: worker 공통 outbox 소비 기반
- MOM-1002: task 수정·삭제 이벤트와 반복 변경 발행 멱등키, no-op 이벤트 정책
- MOM-0956: task projector와 prod E2E
- MOM-0953: legacy/신규 writer 전환·공존 판단과 rollback 조건
- MOM-1006: 공통 task·milestone writer 동시 수정·삭제 경쟁 검증
- MOM-0992: canonical 주소 전환, 레거시 경로 종료, client 재연결

공통 task writer는 생성·실제 수정·삭제에 각각 `task.created`·`task.updated`·`task.deleted`를
발행한다. MCP 어댑터에는 작업 삭제 도구나 별도 outbox writer, 인라인 retrieval projector가
없다. 이벤트·재시도 계약은 [모듈 맵의 outbox 절](../design/module-map.md#outbox)을 따르고,
MOM-0956·0953을 포함한 게이트가 닫힌 뒤 운영 쓰기를 활성화한다.
