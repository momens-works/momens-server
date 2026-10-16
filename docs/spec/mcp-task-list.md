# MCP 목록과 태스크 활동 읽기 도구

기존 `list_tasks`는 유지하고 새 호출에는 `list_tasks_v2`를 사용한다. 기존 호출을 자동으로 전환하지 않는다.
두 도구의 scope는 `mcp:tasks:read`이며, token·활성 grant·현재 workspace membership을 검증한다.

입력 필드는 [도구 schema](../../modules/mcp/src/main/resources/mcp/read-tools.json),
v2 전환 결정은 MOM-1003과 [PR #288](https://github.com/momens-works/momens-server/pull/288),
페이지 분할의 판단 근거·검증 이력은 MOM-1004와 [PR #330](https://github.com/momens-works/momens-server/pull/330)을 참고한다.

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
명시적 null, 문자열 입력 필드에 전달한 문자열이 아닌 값, 정의되지 않은 필드는 도구 오류다.

`project`는 기존과 같이 workspace 내 UUID·PRJ-label·이름으로 찾는다. 담당자 이름은
대소문자를 구분하지 않는 정확 일치, 유일한 부분 일치 순으로 해석하며 중복이면 오류다.
프로젝트·담당자·상태 필터는 AND로 결합한다. 조회는 인증된 workspace의 공개 reader를 사용한다.

## 응답과 전환

성공 응답은 기존 텍스트 형식과 `resultType: complete`를 유지한다. 일치 항목이 없으면
`No tasks match.`를 반환한다. 입력 오류는 `isError: true`이며, 호출자는 안내를 보고 입력을
수정해 다시 호출한다. 같은 잘못된 입력을 자동 재시도하는 계약은 아니다.

기존 입력으로 첫 페이지를 조회할 수 있다. 전체 목록이 필요하면 응답의 cursor가 없어질 때까지
후속 페이지를 조회한다. v2로 옮길 때 전체 조회는 `assignee`를 생략하고,
미할당 조회에만 `none`·`unassign`·`unassigned`를 사용한다. 상태 필터가 불필요하면
빈 문자열 대신 `status`를 생략한다. 기존 `list_tasks` 제거는 이번 범위에 포함하지 않는다.

삭제 프로젝트 처리(MOM-1000)는 별도 작업이다.

## 페이지 이동과 응답 크기

목록은 기본 20개, 최대 100개를 요청할 수 있다. DB에서 필터와 제한을 적용하고 다음 페이지
여부를 확인할 한 행만 추가로 읽는다. 프로젝트·마일스톤·태스크·활동은 생성 시각과 UUID의
내림차순, 멤버는 가입 시각과 사용자 UUID의 오름차순이다. UUID 비교는 PostgreSQL에서 수행한다.

응답에 cursor가 있으면 같은 도구와 같은 필터를 보내 다음 페이지를 조회한다. `limit`은
페이지 사이에 바꿀 수 있다. cursor는 workspace·사용자·도구·필터에 묶이며 권한 증명이 아니다.
매번 현재 grant와 멤버십을 검증한다. 잘못된 cursor는 도구 오류이며, 처음부터 조회하려면
cursor를 생략한다. 배포 후 클라이언트는 `tools/list`를 다시 읽어 새 입력과 활동 조회 도구를
인식해야 한다. 페이지를 넘기는 동안 새로 생성되거나 상태가 바뀐 데이터에 대한 스냅샷
일관성은 제공하지 않는다. 생성 시각·UUID가 같은 정렬 위치를 다시 읽지 않는다.

목록 본문은 UTF-8 48,000바이트, 활동 본문은 32,000바이트 안에서 항목 수를 줄일 수 있다.
각 항목은 4,000바이트, 태스크 상세 본문은 12,000바이트까지 표시하며 넘는 텍스트에는
`[truncated]`를 표시한다. cursor는 실제 표시한 마지막 항목의 다음부터 이어진다. 잘린 항목의
본문을 이어 읽는 cursor는 제공하지 않으므로 원문은 웹 상세에서 확인한다. 전체 도구 텍스트는
안내를 포함해 60,000바이트 이내다. 이 한도는 JSON 직렬화·전송 envelope의 바이트 수가 아니다.
원문 조회와 문자열 조합·UTF-8 변환은 잘림 전에 수행하므로 서버 메모리 사용량의 상한도 아니다.

프로젝트 UUID는 직접 조회한다. 프로젝트 이름·label은 이름과 식별자를 100행씩 읽어 Java의
공백 제거·대소문자 비교로 판정한다. 정확·부분 일치 후보를 종류별 두 개까지 유지하고,
판정이 끝나면 우선순위가 높은 후보 두 개까지 반환한다. DB와 Java의
문자열 비교 차이로 정확 일치가 누락되는 것을 방지한다. 담당자 UUID는 멤버십을 직접 확인한다.
담당자 이메일·이름 해석은 기존 정확 일치 우선·중복 거부를 유지하기 위해 멤버십과
사용자 공개 계약을 100명 단위로 순회한다. 따라서 한 번에 읽는 행과 메모리는 제한되지만,
이 참조 해석의 총 조회량은 각각 workspace의 프로젝트·멤버 수에 비례할 수 있다.
목록 행 수 제한은 DB 스캔·정렬 작업량의 상한을 뜻하지 않는다. 프로젝트·마일스톤 상세 reader는
소유자를 별도로 배치 조회하므로 소유자 행 수에도 목록과 같은 상한이 적용되지는 않는다.

## 태스크 상세와 활동

`get_task`는 최근 활동을 최대 10개와 전체 활동 건수로 보여 준다. 댓글과 진행 기록을 모두
포함한다. 추가 조회는 `list_task_updates`를 사용하며 `mcp:tasks:read` scope가 필요하다.
`get_task`의 cursor를 이어 쓸 때는 응답 안내의 태스크 UUID를 `task`에 넣는다.
`list_task_updates`에서 받은 cursor는 그 호출에 사용한 태스크 참조를 그대로 유지한다.

활동은 최신순이며 작성자와 UTC 시각을 표시한다. 총건수는 해당 호출 시점의 삭제되지 않은
활동 수이며 후속 페이지에서도 COUNT를 다시 조회한다. 목록과 건수는 별도 조회이므로
동시 작성·삭제 시 순간적으로 달라질 수 있다.
태스크 또는 부모 프로젝트가 삭제됐거나 다른 workspace에 속하면 활동을 읽기 전에 거부한다.

## 작업 상세 링크

`get_task`·`create_task`·`update_task` 성공 텍스트는 `url: <절대 URL>` 행을 제공한다.
기존 라벨·상태 정보와 text content 형식은 유지한다. `get_task`에서는 본문·활동보다 앞에
링크를 표시한다. 목록 도구 두 개는 응답 크기를 늘리지 않도록 링크를 반복하지 않는다.
목록에서 선택한 작업의 링크는 `get_task`로 얻는다. 목록 크기 제한·페이지 이동은
위 계약을 따른다.

웹 경로는 `/{workspaceSlug}/projects/{projectUUID}?tab=tasks&task={taskLabel}`이다.
워크스페이스는 인증된 workspace의 현재 slug, 프로젝트는 UUID, 작업은 MOM-label을 사용한다.
라벨이 null인 작업만 UUID로 대체한다. 각 경로 세그먼트와 query 값은 URI 인코딩한다.
이 링크는 접근 권한을 부여하지 않으며 웹 로그인이 필요하다. scope·grant·membership 검증과
workspace 내 작업·활성 프로젝트 확인을 통과한 응답에만 링크를 포함한다.

기준 주소는 `momens.mcp.web-base-uri`(`MOMENS_MCP_WEB_BASE_URI`)로 지정한다.
HTTPS origin 또는 로컬 HTTP loopback origin만 허용하고, 경로(`/` 제외)·query·fragment·userinfo는
허용하지 않는다. 요청 Host나 OAuth·초대 URL에서 주소를 유추하지 않는다.
설정 누락·잘못된 주소는 기동 시 거부한다. 환경별 주입은 [로컬 개발](../local-development.md),
prod 반영 여부는 [운영 준비 대장](../prod-readiness-ledger.md)을 따른다.
