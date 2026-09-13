# 웹 snapshot 계약 (H023)

상태: 계약 확정 (구현 전)

작성일: 2026-08-18

레거시 기준선: `Momens-Works/momens-api@71bbd07614fd2aef4dec726bafdf86c1bd097ba6`

FE 기준선: `momens-fe@d76a2d5`

관련 작업: `MOM-0856`(계약 확정), `MOM-0862`(구현), `MOM-0857`~`MOM-0861`(capability read 기반)

관련 문서: [이관 전략](strategy.md),
[이관 원장](ledger.md),
[첫 웹 read 슬라이스 계약](slice-workspace-read.md),
[API 버저닝](../../spec/api-versioning.md),
[API 응답과 에러 코드](../../spec/api-response-error-codes.md)

## 1. 목적

`GET /workspaces/:id/snapshot`(H023)은 웹 보드의 유일한 실질 read 경로다. FE 조사 결과 웹의
프로젝트·마일스톤·태스크·블로커·메모리 read는 사실상 전부 이 하나로 들어온다. 따라서 웹 read
이관의 중심은 이 endpoint의 계약을 잠그는 것이고, 이 문서가 확정한 응답 계약이
`MOM-0857`~`MOM-0861`의 capability read 기반 요구사항을 도출하는 기준이 된다.

합성 로직의 소유는 `:web`으로 이미 확정됐다([첫 웹 read 슬라이스 계약](slice-workspace-read.md) 5절).
이 문서는 그 위에서 응답 계약과 필드별 소유 capability를 고정한다.

## 2. 레거시 트레이스

기준선 SHA에서 확인한 사실만 적는다.

| 단계 | 위치 | 확인 내용 |
| --- | --- | --- |
| route | `bootstrap/router.go:100` | `r.Group("/workspaces")` + `Use(requireAuth)`. `GET "/:id/snapshot"` → `snapshot.Get` |
| handler | `snapshot/handler.go:71` | 9개 도메인 서비스를 순차 호출해 한 응답으로 합침 |
| handler | `snapshot/handler.go:93` | 워크스페이스 조회가 선두. 비멤버를 목록 쿼리 전에 차단 |
| handler | `snapshot/handler.go:150` | `orEmpty()`로 모든 리스트를 `[]`로 정규화 |
| test | `snapshot/handler_integration_test.go` | edge 있는 task만 번들 생성(`:101`), 비멤버 403(`:150`) |

### 2.1 호출 순서

| # | 호출 | 위치 |
| --- | --- | --- |
| 1 | `workspace.Get` | `workspace/service.go:170` |
| 2 | `workspace.ListMembers` | `workspace/service.go:530` |
| 3 | `project.List` | `project/service.go:156` |
| 4 | `milestone.ListByWorkspace` | `milestone/service.go:56` |
| 5 | `task.ListByWorkspace` | `task/service.go:149` |
| 6 | `blocker.List` | `blocker/service.go:87` |
| 7 | `memory.ListCandidates(status="")` | `memory/service.go:91` |
| 8 | `memory.ListMemories(status="")` | `memory/service.go:416` |
| 9 | `relation.WorkspaceTaskContexts` | `relation/service.go:178` |

1번이 `RequireMember`로 멤버십을 판정하고, 2~9번은 각자 다시 `RequireMember`를 호출한다. 1번을
통과했으면 나머지는 실질적으로 통과한다.

### 2.2 정렬과 soft-delete

| 컬렉션 | 쿼리 | 레거시 정렬 | 레거시 soft-delete |
| --- | --- | --- | --- |
| `members` | `workspace/repository.go:227` | **없음** | 해당 없음 |
| `projects` | `project/repository.go:74` | `created_at DESC` | `deleted_at IS NULL` |
| `milestones` | `milestone/repository.go:86` | `created_at DESC` | 자신 + 소속 project |
| `tasks` | `task/repository.go:83` | `created_at DESC` | `deleted_at IS NULL` |
| `blockers` | `blocker/repository.go:51` | `created_at DESC` | **컬럼 없음** |
| `memory_candidates` | `memory/repository.go:57` | `importance DESC NULLS LAST, created_at DESC` | **컬럼 없음** |
| `memories` | `memory/repository.go:156` | `created_at DESC` | `deleted_at IS NULL` |
| `task_contexts` | `relation/repository.go:121` | **없음** | edge는 `entity_relations.deleted_at`, 번들 내부는 `confirmed_memories`·`source_refs`의 `deleted_at` |

후보·메모리는 `status=""`로 호출되어 **상태 필터가 없다.** `REJECTED`·`EXPIRED` 후보와
`INVALIDATED`·`ARCHIVED` 메모리까지 모두 포함된다.

### 2.3 `owner_user_ids` 폴백

project와 milestone의 폴백이 다르다.

| 대상 | 위치 | 집계 | 비었을 때 |
| --- | --- | --- | --- |
| project | `project/repository.go:37` | `project_owners`를 `created_at, owner_user_id` 정렬로 집계 | `ARRAY[p.owner_id]` |
| milestone | `milestone/repository.go:30` | `milestone_owners`를 `created_at, owner_user_id` 정렬로 집계 | `ARRAY[]::uuid[]` |

milestone에는 `owner_id` 컬럼 자체가 없어 폴백할 대상이 없다. 또 `milestone/service.go:72`의 생성
경로가 owner 미지정 시 소속 project owner를 `milestone_owners`에 실제로 시드하므로, 빈 배열은 그
로직 이전에 만들어진 행에서만 나타난다.

**JSON에서는 빈 배열이 `[]`로 나가지 않는다.** `domain.Milestone.OwnerUserIDs`가
`json:"owner_user_ids,omitempty"`이고 Go의 `omitempty`는 빈 슬라이스도 생략하므로, 이 경우
**키 자체가 사라진다.** project는 폴백이 `ARRAY[p.owner_id]`라 절대 비지 않아 항상 키가 있다.

### 2.4 `task_contexts` 합성

`relation/service.go:178`.

- `entity_relations`에서 `from_entity_type = TASK`, `relation_type = LINKED_TO`,
  `deleted_at IS NULL`인 edge를 한 번에 스캔한다.
- `to_entity_type`이 `MEMORY`면 `memories`, `SOURCE_OBJECT`면 `source_refs`로 모으고 나머지는
  버린다.
- 번들은 **`MEMORY`·`SOURCE_OBJECT` edge가 있는 task에 대해서만** 생성된다. `default: continue`가
  `seenTask` 등록보다 앞이라, 다른 종류의 edge만 가진 task는 번들을 받지 못한다. 번들이 없는 task는
  빈 번들이 아니라 항목 자체가 없다(레거시 테스트가 고정한 동작).
- **`tasks` 테이블과 join하지 않는다.** soft-delete된 task의 번들이 남을 수 있다.
- 번들 순서는 edge 발견 순서이고, edge 쿼리에 `ORDER BY`가 없어 비결정적이다.
- 하이드레이션은 배치 2회(`MemoriesByIDs`, `SourceRefsByIDs`)이며 각각 `created_at DESC`이고
  **둘 다 `deleted_at IS NULL`로 거른다.** 번들 내부 순서는 이 전역 정렬을 따르며 edge 순서가 아니다.
- `taskOrder`는 하이드레이션 **이전에** edge로 확정된다. 따라서 링크 대상이 전부 soft-delete된
  task는 `{"task_id": ..., "memories": [], "source_refs": []}` 빈 번들로 남는다.

하이드레이션 쿼리는 **부분 투영**이다. Go 타입은 top-level과 같은 `domain.ConfirmedMemory`·
`domain.SourceRef`이지만 컬럼을 좁게 SELECT 하고, 나머지 필드는 zero value로 남아 `omitempty`에
걸려 JSON에서 사라진다. 좁은 wire shape는 의도적 타입 설계가 아니라 부분 채움과 `omitempty`가
만들어낸 결과다.

| 하이드레이션 | 위치 | SELECT 컬럼 |
| --- | --- | --- |
| `MemoriesByIDs` | `relation/repository.go:156` | `id, workspace_id, label, memory_type, title, summary, body, status, created_at, updated_at` |
| `SourceRefsByIDs` | `relation/repository.go:182` | `id, workspace_id, source_type, source_object_type, source_object_id, source_url, title, snippet, author_name, author_email, source_created_at, visibility, permission_key, verified_by_user_id, verified_at, created_at, updated_at` |

`memory/repository.go:106`의 top-level `memoryColumns`는 전체 컬럼이다. 즉 같은 메모리가 응답
안에서 두 가지 폭으로 등장한다.

단건 `GET /tasks/:taskId/context`(H072, `relation/service.go:141`)도 같은 하이드레이션을 쓴다.
좁은 투영은 snapshot 전용 동작이 아니다.

### 2.5 에러

| 상황 | 레거시 |
| --- | --- |
| 인증 없음 | 401 `{"error":"unauthorized"}` |
| path id가 UUID가 아님 | 400 `{"error":"invalid workspace id"}` |
| 1번 호출 실패 | **무조건 403** `{"error":"access denied"}` (`snapshot/handler.go:94`) |
| 2~9번 호출 실패 | `httpx.WriteServiceError` (`platform/httpx/response.go:33`) |

1번은 미존재 워크스페이스도, DB 장애도 403으로 매핑한다. **이 endpoint에서 404는 나올 수 없다.**

## 3. FE 사용 실태

`momens-fe@d76a2d5` `src/api/workspaceSnapshot.ts`에서 확인한 사실이다.

- `loadWorkspaceSnapshot`이 snapshot을 먼저 호출하고, **404일 때만** 레거시 fan-out으로 폴백한다
  (`:96`). 폴백 코드에는 "모든 배포 API가 snapshot을 서빙하면 삭제"라는 주석이 달려 있다.
- 폴백은 `getWorkspace` + 6개 목록 + project별 마일스톤·태스크 + task별 context N+1이다. H022,
  H038, H053 등은 이 폴백 안에서만 소비된다.
- `sources`는 **오직 `task_contexts[].source_refs`에서만** 나온다. snapshot에 top-level
  `source_refs` 키가 없다(`:185`).
- `mapSourceRef`(`src/api/mappers.ts:198`)가 읽는 필드는 전부 2.4의 좁은 투영 안에 있다. `text`도
  읽지만 `snippet ?? text` 폴백이고, FE가 source_ref를 얻는 두 경로(snapshot·`client.taskContext`)가
  모두 같은 좁은 쿼리를 쓰므로 **`text`는 어느 경로로도 도달할 수 없다.**
- `confirmedMemories`는 top-level `memories`와 `task_contexts[].memories`를 합친다(`:194`).
  `uniqueById`(`:329`)가 `new Map` 기반이라 **같은 id에서 뒤가 앞을 덮어쓴다.** 좁은 쪽이 뒤에
  오므로 태스크에 링크된 메모리는 좁은 버전이 이기고, `source_ref_ids`·`related_entity_ids`·
  `metadata`·`confirmed_at` 등을 잃는다. 이는 FE 버그이며 `MOM-0876`으로 분리했다.

## 4. 잠근 계약

### 4.1 path와 버저닝

| 레거시 | 신규 |
| --- | --- |
| `GET /workspaces/:id/snapshot` | `GET /api/workspaces/{workspaceId}/snapshot` (`version = "1"`) |

레거시 path alias는 두지 않는다.

### 4.2 인증과 권한

보호 체인이 access token을 읽고 컨트롤러가 `CurrentUser.id(principal)`로 `users.id`를 받는다.
전환기에는 레거시 `session_token` 쿠키를 함께 수용한다([ADR-0017](../../adr/0017-transitional-legacy-session-token-acceptance.md)).

권한 판정은 `WorkspaceMembershipReader.roleOf` 한 번이다. 레거시는 9개 서비스가 각각 `RequireMember`를
호출하지만 결과가 같고 쿼리만 늘어난다.

### 4.3 응답 body

성공 응답은 레거시 shape를 유지한다. 전역 wrapper는 쓰지 않는다. 최상위 키는 9개다.

```json
{
  "workspace": { },
  "members": [],
  "projects": [],
  "milestones": [],
  "tasks": [],
  "blockers": [],
  "memory_candidates": [],
  "memories": [],
  "task_contexts": []
}
```

- 8개 리스트는 비어도 `null`이 아니라 `[]`다. `task_contexts[].memories`와
  `task_contexts[].source_refs`도 마찬가지다. 레거시 `TaskContext`에는 `omitempty`가 없고 두 필드가
  항상 빈 슬라이스로 초기화되기 때문이다. Java에서는 명시하지 않으면 `null`이 나가므로 주의한다.
- `workspace`는 래퍼 없는 단일 객체다. 200 응답에서 `null`이 되는 경우는 없다.
- 각 원소의 필드 집합은 대응하는 레거시 목록 endpoint와 동일하다. 값이 없는 nullable 필드는
  레거시 `omitempty` 동작을 따라 필드를 생략한다.
- **원소 내부의 빈 컬렉션 필드는 `[]`·`{}`가 아니라 키를 생략한다.** Go의 `omitempty`가 빈
  슬라이스와 빈 맵을 모두 생략하기 때문이다. 해당 필드는 다음과 같다.
  - 배열: `owner_user_ids`(project·milestone), `source_ref_ids`·`related_entity_ids`(memory·candidate)
  - 맵: `metadata`(project, memory_candidate, memory, source_ref 4개 타입)

  위 두 항목(최상위 8개 리스트, 번들의 두 배열)만 예외로 `[]`를 보장한다.

필드별 소유 capability는 5절에 정리한다.

#### `projects[].progress` — 키를 내보내지 않는다

레거시 `domain.Project.Progress`에는 `omitempty`가 없어 값이 `0`이어도 항상 실린다. 그럼에도
**신규는 이 키를 내보내지 않는다.** 위의 "필드 집합은 레거시와 동일하다"에 대한 유일한 예외다.

근거는 둘이다.

- **웹이 이 값을 읽지 않는다.** `momens-fe`의 `mapProject`(`src/api/mappers.ts`)가 이 필드만
  매핑에서 빼고 있고, 도메인 `Project` 타입에도 `progress`가 없다. 화면의 프로젝트 진행률은
  `taskCompletionRatio`(`src/domain/selectors.ts`)가 태스크에서 직접 계산한다. API 타입
  `ApiProject.progress`가 optional이라 키가 없어도 타입 검사와 런타임 모두 영향이 없다.
- **이 서버는 저장값을 쓰지 않는다.** 진행률은 태스크 기준 계산이고 `projects.progress`는 읽지도
  쓰지도 않는 레거시 컬럼으로 유지한다([ADR-0013](../../adr/0013-project-progress-derivation.md)).
  ADR이 "웹 이관 시 진행률 정책과 함께 다시 판단한다"로 유예한 지점을 여기서 정한다.

계산값을 채우는 선택지도 있었으나, 아무도 읽지 않는 값을 위해 project별 태스크 집계를 4.7의 쿼리
예산에 얹게 되므로 택하지 않았다. 소비자가 생기면 `TaskProgressReader.progressOf`가 이미 있으므로
계산값 노출로 올라갈 수 있다.

`../e2e` 대조 스위트에서는 이 키를 의도적 diff로 등록한다.

### 4.4 정렬과 필터 — 확정

| 컬렉션 | 확정 정렬 | 확정 필터 |
| --- | --- | --- |
| `members` | **`workspace_members.created_at ASC, user_id ASC`** | 없음 |
| `projects` | `created_at DESC` | `deleted_at IS NULL` |
| `milestones` | `created_at DESC` | 자신 + 소속 project 양쪽 |
| `tasks` | `created_at DESC` | `deleted_at IS NULL` |
| `blockers` | `created_at DESC` | 없음 |
| `memory_candidates` | `importance DESC NULLS LAST, created_at DESC` | 없음 |
| `memories` | `created_at DESC` | `deleted_at IS NULL` |
| `task_contexts` | **대응 task의 `created_at DESC`** | edge·task·번들 내부 원소 **모두 soft-delete 제외** |
| `task_contexts[].memories`·`[].source_refs` | **각 `created_at DESC`** | soft-delete 제외 |

`memory_candidates`의 `importance DESC NULLS LAST`는 유일한 비-`created_at` 정렬이다. 보드가
중요도 순으로 후보를 보여주는 동작이므로 그대로 보존한다.

후보·메모리에 상태 필터를 두지 않는 동작도 보존한다. 클라이언트가 상태로 분기한다.

`members`와 `task_contexts`는 레거시에 `ORDER BY`가 없다. 이는 보존할 계약이 아니라 재현할 수 없는
비결정성이므로 신규에서 정렬을 고정한다.

- `members`: 가입 시각 오름차순으로 고정한다. `user_id`는 tie-break다. owner가 앞에 오는 것은
  생성자가 첫 멤버로 등록되는 데서 오는 부수 효과일 뿐이고 소유권 이양·생성자 탈퇴로 깨지므로
  계약이 아니다. owner 우선 노출이 필요하면 클라이언트가 `role`로 정렬한다.
- `task_contexts`: 4.5의 task 필터를 거친 뒤 `tasks` 배열과 같은 순서로 고정한다. 클라이언트가 두
  배열을 나란히 읽을 수 있다.

### 4.5 `task_contexts` — 확정

**`tasks` 배열에 없는 task의 번들은 내보내지 않는다.** 레거시는 edge만 스캔해 soft-delete된
task의 번들이 남고, 클라이언트는 그 task를 `tasks` 배열에서 찾지 못해 고아 번들이 된다.

결과 동작만 고정하되, 모듈 의존을 만드는 `:context` 안의 `tasks` 조인은 배제한다. `:web`이 같은
응답의 `tasks` 목록을 이미 갖고 있으므로 그 id 집합과 교집합을 취하면 추가 쿼리도 모듈 의존도
없고, 4.4의 정렬 고정이 함께 성립한다.

`MEMORY`·`SOURCE_OBJECT` edge가 없는 task는 항목 자체가 없다(빈 번들이 아니다). 레거시 테스트가
고정한 동작이므로 보존한다. 반대로 edge는 있지만 링크 대상이 전부 soft-delete된 task는 두 배열이
빈 번들로 남는다(2.4). 두 경우를 구분해 구현한다.

번들 내부 원소의 필드 폭은 **memory와 source_ref가 서로 다르다.**

| 대상 | 확정 | 근거 |
| --- | --- | --- |
| `source_refs` | **좁은 전용 타입.** 2.4의 컬럼 집합 그대로 | 유일한 출처이지만 FE 소비 필드를 모두 담는다. `text`는 스니펫이 아니라 수집 원문 전체(`000002_retrieval_projection.sql:18`)라 페이로드가 크게 부풀고, FE에서 도달 불가능한 폴백이다 |
| `memories` | **넓은 투영.** top-level `memories`와 같은 필드 집합 | 중복 출처다. 좁게 두면 클라이언트가 두 출처를 합칠 때 좁은 쪽이 넓은 쪽을 덮어써 관계 정보가 사라진다(3절, `MOM-0876`) |

비대칭이지만 이유가 다르다. `source_refs`는 유일한 출처라 좁혀도 잃을 것이 없고, `memories`는
중복 출처라 좁으면 원본을 덮어쓴다.

memory를 넓히는 것은 필드 추가이므로 하위호환이다. FE 수정 없이 `MOM-0876`의 증상이 사라진다.
**대가는 페이로드 중복이다.** 태스크에 링크된 메모리가 top-level과 번들에 전체 필드로 두 번
실린다. FE 폴백 코드가 삭제되면 `task_contexts`의 memory를 id 참조로 축소해 중복을 없앤다(별도 과제).

Java에는 `omitempty`가 없으므로 이 폭 차이는 명시적으로 선언해야 한다. `source_refs`용 좁은 응답
타입을 따로 두고, 넓은 타입을 부분만 채워 `null`로 내보내는 방식은 쓰지 않는다. 클라이언트가 "값이
없음"과 "싣지 않음"을 구분할 수 없기 때문이다.

### 4.6 에러 응답 — Standard 모드

[첫 웹 read 슬라이스 계약](slice-workspace-read.md) 4.4에서 FE와
합의한 규칙을 승계하고, 거기에 없던 서버 오류 한 행을 더한다. 같은 워크스페이스 리소스에 다른
규칙을 둘 이유가 없다.

| 상황 | 레거시 | 신규 |
| --- | --- | --- |
| 인증 없음·토큰 무효 | 401 `{"error":"unauthorized"}` | 401 `AUTH_UNAUTHORIZED` / `AUTH_INVALID_TOKEN` |
| path id가 UUID가 아님 | 400 `{"error":"invalid workspace id"}` | 400 `COMMON_BAD_REQUEST` |
| 워크스페이스 없음 | 403 `{"error":"access denied"}` | **404 `WORKSPACE_NOT_FOUND`** |
| 멤버가 아님 | 403 `{"error":"access denied"}` | **403 `AUTH_FORBIDDEN`** |
| 조회 중 서버 오류 | 403 `{"error":"access denied"}` | **500 `COMMON_INTERNAL_SERVER_ERROR`** |

마지막 행은 레거시 동작을 **버린다.** `snapshot/handler.go:94`가 DB 장애까지 403으로 매핑하는 것은
장애를 권한 오류로 위장해 관측을 망가뜨린다. 레거시 shape 보존은 성공 응답에 대한 약속이다.

존재 확인이 멤버십 판정보다 앞선다. 워크스페이스 존재 여부가 드러나지만 식별자가 추측 불가능한
UUID라 열거 위험이 없다는 판단도 선례를 따른다.

### 4.7 쿼리 예산

레거시는 약 **20 쿼리**를 낸다. 9개 서비스가 각각 `RequireMember`를 부르고 이것이 독립된
`SELECT EXISTS`(`access/repository.go:36`)라 멤버십만 9회, 여기에 목록 9회와 하이드레이션 2회가
더해진다. project·milestone의 `owner_user_ids`는 행당 상관 서브쿼리로 별도 실행된다.

신규 구현의 예산은 레거시 대비가 아니라 **절대 상한 14 쿼리**로 못 박는다. 회귀 테스트는 이
숫자를 임계값으로 쓴다.

| 구간 | 쿼리 수 |
| --- | --- |
| 워크스페이스 존재 확인 | 1 |
| 멤버십 판정 (`WorkspaceMembershipReader.roleOf`) | 1 |
| 목록 7종 (members·projects·milestones·tasks·blockers·candidates·memories) | 7 |
| `task_contexts` edge 스캔 | 1 |
| 번들 하이드레이션 (memory·source_ref) | 2 |
| `owner_user_ids` 배치 조회 (`project_owners`·`milestone_owners`) | 2 |

마지막 2회는 **허용한다.** 레거시는 상관 서브쿼리로 목록 쿼리 안에서 해결하지만, JPA에서 같은
모양을 재현하려 들면 구현이 뒤틀린다. 워크스페이스당 1회씩 배치로 모아 읽는 편이 낫고 N+1도
아니다. 인라인 집계로 구현해 12에 맞추는 것도 계약 위반이 아니다.

특히 다음을 금지한다.

- task별 context 조회 (레거시가 이미 제거한 N+1)
- project별 마일스톤·태스크 조회 (FE 폴백이 하던 fan-out)
- `owner_user_ids`를 위한 project·milestone **행별** 추가 조회 (배치 2회는 위 표에서 허용)

## 5. capability read 기반 요구사항

합성은 `:web`이 소유하고, 데이터는 capability 모듈의 public API로만 얻는다. 각 구획이 요구하는
read 기반과 담당 작업은 다음과 같다.

| 응답 구획 | 소유 capability | read 기반 작업 | 비고 |
| --- | --- | --- | --- |
| `workspace` | `:workspace` | 완료 (`MOM-0851`) | `WorkspaceReader.findById` 재사용 |
| `members` | `:workspace` | `MOM-0864` 선행 또는 `MOM-0862`에서 추가 | `users` 조인 필요. 4.4 정렬 고정 |
| `projects` | `:project` | `MOM-0857` | 웹 컬럼·`project_owners` 매핑. `progress`는 4.3의 예외대로 제외 |
| `milestones` | `:project` | `MOM-0858` | **`owner_user_ids` 폴백이 project와 다름**(2.3). `[]`가 아니라 **키 생략**으로 폴백한다 |
| `tasks` | `:project`의 `task` 하위 경계 | `MOM-0861` | |
| `blockers` | `:project` | `MOM-0859` | soft-delete 컬럼 없음 |
| `memory_candidates` | `:memory` | `MOM-0860` | `importance` 정렬, soft-delete 컬럼 없음 |
| `memories` | `:memory` | `MOM-0860` | 넓은 투영. `task_contexts`도 같은 read 기반을 쓴다 |
| `task_contexts` | `:context` | `MOM-0862`에서 신설 | edge 스캔 + 배치 하이드레이션. source-ref 조회는 `:source` public API |

`MOM-0858`의 티켓 본문에는 milestone 폴백 차이가 적혀 있지 않다. 구현 시 이 문서 2.3을 기준으로
한다.

#### milestone read 기반의 public API는 워크스페이스 목록 하나다

`MOM-0858` 티켓 본문은 "프로젝트별 목록·단건 조회"를 적고 있으나, **워크스페이스 단위 목록만
둔다.** `MilestoneReader.listDetailsByWorkspaceId` 하나다.

FE 기준선에서 확인한 소비 실태가 근거다.

| 레거시 endpoint | FE 클라이언트 | 호출자 |
| --- | --- | --- |
| H051 `GET /projects/:projectId/milestones` | `client.listMilestones`(`src/api/client.ts:276`) | `loadWorkspaceSnapshotLegacy`(`src/api/workspaceSnapshot.ts:130`) **하나뿐** |
| H056 `GET /milestones/:milestoneId` | **없음** | 없음 |

H051의 유일한 호출자는 snapshot이 없을 때만 타는 폴백이고, FE가 스스로
`Delete once every deployed API serves /snapshot`으로 표시해 둔 코드다
(`src/api/workspaceSnapshot.ts:101`). H056은 클라이언트에 메서드조차 없다. 실사용 경로는
`snapshot.milestones` 하나다.

`ProjectDetailReader`가 같은 근거(H047 미호출)로 단건 조회를 두지 않은 것과 같은 기준이다.
소비자가 생기면 그때 올린다.

#### `milestones[].progress`는 내보낸다

4.3이 `projects[].progress`를 제외한 것과 다르다. 마일스톤 진행률은 레거시 write 경로가 실제로
유지하는 저장값이고(`milestone/service.go`의 `normalizeProgress`), `momens-fe`의
`mapMilestone`(`src/api/mappers.ts:75`)이 이 값을 읽는다. 저장값을 쓰지 않기로 한 결정
([ADR-0013](../../adr/0013-project-progress-derivation.md))은 `projects.progress`에만 해당한다.

## 6. 테스트

- 컨트롤러 테스트: 9개 키의 존재, 빈 컬렉션 `[]`, 4.6의 에러 매핑
- app 레벨 통합테스트: 합성 정합(각 구획이 같은 워크스페이스 데이터에서 나옴), 4.4의 정렬,
  soft-delete 필터, `task_contexts`가 edge 있는 task만 담고 soft-delete된 task를 제외하는 동작
- 쿼리 예산 회귀: 4.7을 넘지 않는지 확인
- 검증 오라클은 `../e2e` 대조 스위트다. 레거시에 characterization 테스트가
  `snapshot/handler_integration_test.go` 2개뿐이므로 이 문서가 계약의 출처다

## 7. 미결정

- `members`를 `:workspace` read 기반에 넣을지, `MOM-0864`(멤버 관리 이관)의 read 모델을 기다릴지.
  `MOM-0862` 착수 시점에 `MOM-0864` 상태를 보고 정한다.
- H022의 전환 여부. snapshot이 제공되면 FE 폴백이 사라지고 H022의 유일한 소비자가 없어진다.
  폴백 코드 삭제는 FE 작업이므로 `MOM-0862` 머지 후 별도로 판단한다.
