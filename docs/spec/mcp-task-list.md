# MCP 작업 목록 읽기 도구

기존 `list_tasks`는 유지하고 새 호출에는 `list_tasks_v2`를 사용한다. 기존 호출을 자동으로 전환하지 않는다.
두 도구의 scope는 `mcp:tasks:read`이며, token·활성 grant·현재 workspace membership을 검증한다.

입력 필드는 [도구 schema](../../modules/mcp/src/main/resources/mcp/read-tools.json),
전환 결정과 검증 이력은 MOM-1003과 [PR #288](https://github.com/momens-works/momens-server/pull/288)을 참고한다.

## 입력 계약

| 입력 | `list_tasks` | `list_tasks_v2` |
| --- | --- | --- |
| `assignee` 생략·공백 | 전체 담당자 | 전체 담당자 |
| `assignee=none/unassign/unassigned` | 전체 담당자 | 담당자가 없는 작업만 |
| `assignee=me` | 현재 사용자 | 현재 사용자 |
| 담당자 UUID·이메일·이름 | workspace 멤버 참조 해석 | 동일 |
| `status` 생략 | 전체 상태 | 전체 상태 |
| `status` 공백 | 전체 상태 | 도구 오류 |
| 알 수 없는 `status` | 정상 빈 목록 | 도구 오류와 허용값 안내 |

문자열은 앞뒤 공백을 제거한다. 상태와 담당자 별칭은 대소문자를 구분하지 않는다.
상태 schema는 문자열 타입과 설명으로 `TaskStatus` 값인 `backlog`, `todo`, `in_progress`,
`done`, `cancelled`를 안내한다. 클라이언트가 공백·대소문자 변형을 호출 전에 차단하지 않도록
`enum`은 두지 않는다. 서버가 입력을 정규화한 뒤 이 집합을 검증한다.
`progress`·`in-progress` 별칭은 읽기 도구에서 허용하지 않는다.
명시적 null, 문자열이 아닌 값, 정의되지 않은 필드는 도구 오류다.

`project`는 기존과 같이 workspace 내 UUID·PRJ-label·이름으로 찾는다. 담당자 이름은
대소문자를 구분하지 않는 정확 일치, 유일한 부분 일치 순으로 해석하며 중복이면 오류다.
프로젝트·담당자·상태 필터는 AND로 결합한다. 조회는 인증된 workspace의 공개 reader를 사용한다.

## 응답과 전환

성공 응답은 기존 텍스트 형식과 `resultType: complete`를 유지한다. 일치 항목이 없으면
`No tasks match.`를 반환한다. 입력 오류는 `isError: true`이며, 호출자는 안내를 보고 입력을
수정해 다시 호출한다. 같은 잘못된 입력을 자동 재시도하는 계약은 아니다.

기존 전체 조회 호출은 그대로 사용할 수 있다. v2로 옮길 때 전체 조회는 `assignee`를 생략하고,
미할당 조회에만 `none`·`unassign`·`unassigned`를 사용한다. 상태 필터가 불필요하면
빈 문자열 대신 `status`를 생략한다. 기존 `list_tasks` 제거는 이번 범위에 포함하지 않는다.

삭제 프로젝트 처리(MOM-1000), 페이지 분할(MOM-1004), 활동 출력(MOM-1005)은 별도 작업이다.
