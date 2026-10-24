# 모바일 API 명세

이 문서는 모바일 앱의 호출 흐름과 상태·재시도·표시 의미를 설명합니다.
요청·응답 필드, 필수 여부, 허용 값과 오류 예시는 [OpenAPI 스냅샷](openapi.json)의 해당 경로에서
확인합니다. Swagger UI와 스냅샷 사용 방법은 [OpenAPI 안내](openapi.md#스냅샷-고정)를 따릅니다.

## 공통

- Base URL: `/api`
- 모바일 기능 API: `/api/mobile/*`
- 인증 API: `/api/auth/*`
- API version header: `API-Version: 1`
- 인증 header: `Authorization: Bearer {access_token}`
- Content-Type: `application/json`
- 필드 표기: `snake_case`
- 시각 값: ISO 8601 UTC의 `Z` 표기로 반환하며, 표시 시간대 변환은 클라이언트가 담당합니다.
- 응답 형식: [공통 응답 규칙](api-response-error-codes.md)을 따릅니다.

로그인·토큰 갱신·로그아웃과 앱 업데이트 확인은 `Authorization` 헤더가 필요하지 않습니다.
경로·버전 규칙은 [API 버저닝](api-versioning.md)을 따릅니다.

### 에러 응답

엔드포인트별로 반환할 수 있는 에러 코드는 [OpenAPI 스냅샷](openapi.json)의 operation별 실패 응답 예시에서 확인합니다. 에러 응답 형식과 에러 코드 규칙은 [API 응답과 에러 코드](api-response-error-codes.md)를 따릅니다.

## Enum

### Signal

| 이름 | 값 |
| --- | --- |
| `type` | `risk`, `decision`, `change`, `question` |
| `evidence.source` | `slack`, `github`, `figma`, `notion`, `file` |
| `action_command` | `convert-to-task`, `dismiss` |
| `action` | `convert_to_task`, `dismiss` |

`action_command`는 action endpoint의 path segment에 쓰는 명령 값입니다. `action`은 action 처리 결과와 저장
ledger의 enum 값입니다.

Signal type에 따라 앱이 다음처럼 화면 라벨을 정합니다. 이 라벨은 처리 상태나 목록 필터가 아닙니다.

| type | 화면 라벨 |
| --- | --- |
| `risk`, `change` | `Needs action` |
| `decision` | `Needs review` |
| `question` | `Needs decision` |

화면설계서의 Signal 카테고리 표시는 `type`의 첫 글자만 대문자로 바꿔 씁니다.

| type | 카테고리 표시 |
| --- | --- |
| `risk` | `Risk` |
| `decision` | `Decision` |
| `change` | `Change` |
| `question` | `Question` |

### Task

입력의 허용 값은 OpenAPI의 `CreateTaskRequest`·`UpdateTaskRequest`를 따릅니다.
응답에서 `urgent`를 `high`로 표시하고 웹 작업의 미지정 `role`을 `null`로 반환하는 차이는
아래 작업 조회 절을 참고합니다.

### Membership

| 이름 | 값 |
| --- | --- |
| `role` | `owner`, `admin`, `member` |

### Material

| 이름 | 값 |
| --- | --- |
| `source` | `slack`, `github`, `figma`, `notion`, `file` |

`source`는 연결된 소스 원본의 출처입니다. Signal 근거의 `evidence.source`와 같은 source_ref의 출처이므로 값 집합도
동일합니다. 화면에 표시하는 출처 라벨과 아이콘은 앱이 `source`에서 생성합니다.

연결된 소스 화면은 figma, slack, github, file을 표시합니다. 다만 서버는 source_ref의 출처를 그대로 반환하므로, 앱은
목록에 없는 값이 오더라도 화면이 깨지지 않도록 처리합니다.

## 인증

### POST /api/auth/google/token

Google ID token을 Momens access/refresh token으로 교환합니다.

### POST /api/auth/refresh

Refresh token으로 access/refresh token을 재발급합니다.

### POST /api/auth/logout

Refresh token을 폐기합니다.

## 앱 업데이트

### GET /api/mobile/app-update

앱을 실행할 때 현재 앱 버전이 해당 플랫폼의 최소 지원 버전보다 낮은지 확인합니다. 로그인 전에도 호출할 수 있도록 `Authorization` 헤더가 필요하지 않습니다.

- 업데이트하지 않은 앱도 계속 호출하는 API이므로 경로, 쿼리 파라미터, 응답 형식은 변경하지 않습니다. 이후 필요한 정보는 optional 필드를 추가하는 방식으로 확장합니다.
- 최소 지원 버전은 서버 설정(`momens.mobile.app-update.minimum-versions`)으로 관리하며, 하위 호환성을 유지할 수 없는 서버 변경이 필요한 경우 같은 PR에서 올립니다.
- `update_required`가 `true`일 때 사용자에게 업데이트를 안내하는 방식은 앱에서 결정합니다.
- 지원하지 않는 플랫폼, 형식이 올바르지 않은 앱 버전, 필수 쿼리 파라미터 누락은 `COMMON_VALIDATION_FAILED`로 응답하고 `details.fields`에 해당 쿼리 파라미터 이름을 담습니다. `platform`은 `ANDROID`처럼 enum 상수 이름으로 전달해도 허용합니다.

쿼리 형식은 OpenAPI의 `mobileCheckAppUpdate`를 따릅니다. 앱 버전은 점으로 구분된 정수 구성요소
1~3개이며, 각 구성요소는 최대 9자리입니다. 생략된 구성요소는 0입니다(`1.0`과 `1.0.0`은 같음).
각 구성요소를 정수로 비교하므로 `1.10`은 `1.9`보다 높습니다. 0은 허용하지만 선행 0(`01` 등)과
접미사(`-beta`, `+5`)는 허용하지 않습니다.

## 모바일 진입

### GET /api/mobile/bootstrap

로그인 후 앱 진입에 필요한 사용자 정보와 프로젝트 목록을 조회합니다.

- `projects`는 접근 가능한 프로젝트를 생성 최신순으로 담습니다. `role`은 소속 workspace 멤버십 role입니다.
- `default_project_id`는 접근 가능한 프로젝트 중 가장 최근에 만든 것을 선택합니다.
- 접근 가능한 프로젝트가 없으면 `200`으로 `default_project_id`는 `null`, `projects`는 빈 배열을
  반환하고, 빈 화면 처리는 앱이 담당합니다(2026-07-04 가결정, 기획 확인 후 확정).
- 토큰은 유효하지만 사용자가 삭제된 경우 `USER_NOT_FOUND`로 응답합니다. `GET /api/me`와 동일한 동작입니다.

## 계정

### GET /api/mobile/me

마이페이지에 표시할 현재 로그인한 사용자의 계정 정보를 조회합니다.

- 프로필 사진이 없는 경우에도 `avatar_url` 필드를 생략하지 않고 `null`로 반환합니다.
- workspace와 project 조회에 의존하지 않으므로, 프로젝트 조회에 실패하더라도 계정 정보를 조회할 수 있습니다.
- 인증 토큰은 유효하지만 해당 사용자가 삭제된 경우 `USER_NOT_FOUND`를 반환합니다. `GET /api/mobile/bootstrap`과 동일한 처리 방식입니다.

## 프로젝트

### GET /api/mobile/projects/{projectId}/members

작업 담당자 선택에 사용할 프로젝트 멤버 목록을 조회합니다.

- `members`의 범위는 project가 속한 workspace의 멤버십입니다. 별도 project 멤버 테이블을 두지
  않는 권한 요구사항과 같은 기준입니다.
- 정렬은 이름 오름차순이고, 같은 이름은 id 오름차순으로 순서를 고정합니다(2026-07-04 가결정).
- `query`는 앞뒤 공백을 지운 뒤 이름 부분 일치로 거르고 대소문자를 무시합니다. 없거나 공백이면
  전체 멤버를 반환합니다(2026-07-04 가결정).
- `avatar_url`은 값이 없어도 `null`로 항상 포함합니다(bootstrap과 동일).
- 레거시 `GET /workspaces/:id/members`와 달리 email, role, 시각을 내리지 않습니다. 담당자 선택
  bottom sheet가 쓰는 값(id, 이름, 아바타)만 담는 신규 계약입니다(의도된 차이).
- project가 없거나 삭제된 경우 `PROJECT_NOT_FOUND`로 응답합니다. project는 존재하지만 요청 사용자가 해당 workspace의 멤버가 아닌 경우 `AUTH_FORBIDDEN`으로 응답합니다.

## 시그널

### GET /api/mobile/projects/{projectId}/signals

프로젝트의 시그널 목록을 조회합니다.

MVP에서는 아직 처리되지 않은 시그널만 반환합니다. `convert-to-task` 또는 `dismiss`로 처리된 시그널을
다시 보는 inbox/필터 흐름은 MVP 이후로 둡니다.

정렬은 최신 Signal이 먼저 오도록 생성 시각 내림차순을 기본으로 합니다. 생성 시각이 같으면 id 내림차순으로 순서를 고정합니다.

시그널 탭은 무한 스크롤로 목록을 이어서 보여 주고, 서버는 cursor pagination으로 한 페이지씩 반환합니다. 첫 요청은 `cursor` 없이 보내고, 목록 끝에 도달하면 직전 응답의 `next_cursor`를 `cursor`로 전달해 다음 페이지를 조회합니다. `next_cursor`가 `null`이면 더 조회할 데이터가 없습니다. 기본 페이지 크기는 5, 최대 페이지 크기는 50입니다. `limit`과 `cursor`의 해석 규칙은 브리프 시그널 요약과 동일합니다.

형식이 잘못된 `cursor`와 음수 `limit`은 `COMMON_VALIDATION_FAILED`(400)로 응답합니다.

카드의 `Needs action`, `Needs review`, `Needs decision` 라벨은 응답의 `type`에서 앱이 파생합니다. 서버가
별도 처리 상태 필드를 내려주지 않습니다.

`impact`는 프로젝트에 미칠 영향 요약이며 목록 카드의 보조 문구로도 사용합니다. `impact`는 worker가
Signal과 함께 생산하고, worker가 준비되지 않은 MVP 환경에서는 같은 backing 계약의 fixture가 채웁니다.
`minsu_suggestion`은 아직 생성 기능이 구현되지 않아 목으로 처리합니다.
서버 내 `minsu`의 task draft 생성과는 별도 기능입니다. 서버는 근거 없는 문구를 임의 생성하지 않습니다.

응답 항목에서는 `project_id`를 생략하지만 Signal backing의 `project_id`는 유지합니다. 서버가 경로의 프로젝트로
목록을 필터링하고, 원탭 전환 시 task를 어느 프로젝트에 만들지 결정하는 내부 귀속 정보이기 때문입니다.
Signal 처리 이력은 사용자별이 아닌 프로젝트 단위로 적용합니다. task 생성·처리 이력·outbox는
같은 트랜잭션으로 반영하며, 클라이언트가 `Idempotency-Key`를 보낼 필요는 없습니다.

#### Query

| 이름       | 필수  | 설명                                                                                                  |
| -------- | --- | --------------------------------------------------------------------------------------------------- |
| `cursor` | 아니오 | 이전 응답의 `next_cursor`. 없으면 첫 페이지를 조회합니다.                                                             |
| `limit`  | 아니오 | 페이지 크기. 없거나 0이면 기본값 5를 사용하고, 상한은 50입니다. 50을 초과하면 50으로 제한하며, 음수이면 `COMMON_VALIDATION_FAILED`로 응답합니다. |

### GET /api/mobile/signals/{signalId}

시그널 상세 bottom sheet에 필요한 정보를 조회합니다.

상세 상단의 `Needs action` 등 라벨은 Signal type 기반 표시 라벨이며, 처리 상태가 아닙니다.

`impact`는 Signal 전체가 프로젝트에 미칠 영향 요약입니다. `evidence[].details.impact`는 해당 근거 한 건에서
관찰한 영향이라 범위가 다릅니다. `details.target`, `details.change`, `details.impact`는 worker 또는 같은 backing
계약의 fixture가 생산하며 각 값은 공백 포함 30자 이하입니다. 서버는 값을 자르거나 임의 생성하지 않습니다.

근거 행 헤더는 도구 이름(`source`)으로 표시하므로 원천 문서 제목(`source_title`)은 내려주지 않습니다.
`occurred_at`, `source_url`은 원천에 값이 없으면 `null`일 수 있습니다. 상대 시간 라벨은 앱이
`occurred_at`으로 렌더합니다. 근거 개수에 따른 펼침·접힘·빈 상태도 앱이 담당합니다.

project는 모바일의 현재 context로 고정되어 있고, description·task draft·action 목록은 상세 화면에서 사용하지
않으므로 응답하지 않습니다. 이는 응답 계약에서만 제외하는 것이며 backing의 `project_id`는 목록 필터와 task
귀속에 계속 사용합니다. 처리된 Signal을 다시 보는 inbox는 MVP 이후 범위이므로 이미 처리된 Signal도
`SIGNAL_NOT_FOUND`로 응답합니다.

### POST /api/mobile/signals/{signalId}/actions/convert-to-task

시그널을 원탭으로 작업에 등록합니다. 요청 body는 없습니다.

동기 경로에서는 서버 내 `minsu` 모듈이 생성한 task draft(`title`, `role`, `priority`)로 작업을
만듭니다. 생성 비활성·설정 무효·입력 부족·생성 실패·출력 무효 시에는 fallback(`title`은
정규화된 시그널 제목으로 최대 15자, `role`은 `pm`, `priority`는 `medium`)을 사용합니다.
비동기 경로에서는 fallback으로 먼저 작업을 생성하고 후속 생성을 진행합니다. 생성 결과는
`title`, `role`, `priority`가 초기 값과 같을 때만 반영하며, 생성 실패나 선행 편집 등으로
반영되지 않을 수 있습니다. 세 필드 중 하나라도 바뀌면 결과 전체를 폐기하며, 다른 필드만 편집하거나
세 필드를 원래 값으로 되돌린 편집은 감지하지 못합니다. 원장 적재에 실패하면 작업 전환 전체가
롤백됩니다. 생성된 title은 공백 포함 최대 15자이며, draft는 Signal backing에
저장하지 않습니다.

클라이언트는 title·role·priority를 선택하거나 전송하지 않습니다.

`draft_status`는 민수가 이 작업의 제목을 더 손볼 것이 남았는지를 알립니다. 값은 `generating`과
`ready` 둘뿐이며, 생성이 실패했는지 사용자가 먼저 편집했는지는 구분하지 않고 모두 `ready`입니다.
서버가 보장하는 것은 셋입니다.

- `draft_status`는 반드시 `ready`에 도달합니다. `generating`에 갇히지 않습니다.
- **정상 종료에서는** `ready`와 함께 돌려준 `title`이 최종 값입니다. 여기서 재조회를 멈춰도 갱신을
  놓치지 않습니다. 예외는 생성이 deadline을 넘겨 닫힌 경우 하나입니다. 이때는 뒤늦게 커밋된 반영으로
  `title`이 한 번 더 바뀔 수 있습니다. deadline 발동 자체가 서버 경보 대상인 이상 상황이며, 사용자에게는
  "다음 상세 진입에서 제목이 달라 보임" 수준입니다.
- `generating` 동안에도 `title`은 항상 유효한 값입니다. 로딩을 표시하지 않고 그대로 렌더해도 됩니다.

`generating`을 어떻게 표시할지와 재조회 간격은 앱이 정합니다. 결과를 확인하는 경로는 이 응답과
작업 상세 둘뿐이고, **보드·목록에는 `draft_status`가 없습니다.** 보드 카드의 title은 사용자가
상세에 들어가지 않으면 이전 값인 채로 있다가 다음 목록 조회에서 조용히 바뀝니다.

최초 전환은 `201`, 같은 시그널의 같은 액션 재요청은 `200`으로 기존 작업을 반환합니다(멱등).
이미 다른 액션으로 처리된 시그널은 `409 SIGNAL_INVALID_STATE`입니다.

### POST /api/mobile/signals/{signalId}/actions/dismiss

제안된 시그널을 MVP 흐름에서 수용하지 않고 목록에서 삭제 처리합니다. 모바일 화면의 버튼 라벨은 `삭제`지만
서버 action 이름은 `dismiss`입니다. 이 액션은 물리 삭제가 아니고 시그널이 잘못됐다고 확정하는 것도 아니라,
사용자가 현재 시그널을 task로 전환하지 않겠다는 처리 기록입니다. 삭제 처리한 시그널을 다시 보는 inbox
흐름은 MVP 이후로 둡니다.

`SIGNAL_INVALID_STATE`(409)는 Signal이 이미 다른 액션으로 처리되었거나, 현재 상태에서 요청한 액션을 수행할 수 없는 경우 반환합니다. 같은 액션을 다시 요청한 경우에는 `200` 멱등 응답으로 처리합니다.

같은 시그널에 dismiss를 재요청하면 같은 결과를 멱등하게 반환합니다.

## 브리프

### GET /api/mobile/projects/{projectId}/brief

프로젝트 브리프 화면의 초기 로드에 필요한 정보를 조회합니다. 쿼리 파라미터는 없습니다. 시그널 요약의
필터 전환과 더보기 페이지 이동은 아래 하위 엔드포인트가 담당합니다.

브리프는 오늘의 브리프라, 시그널 요약은 그날(당일) 생성된 시그널을 처리 여부와 무관하게 집계합니다. 작업 전환이나 dismiss와 상관없이 그날 온 시그널을 유지하고, 소프트 삭제된 시그널은 제외합니다. 하루 경계는
Asia/Seoul 기준이라, `created_at`(UTC 저장)을 KST 하루로 잘라 그 범위의 시그널을 담습니다. 이 집계 기준은 시그널
탭(처리 대기)과 다릅니다. 시그널 탭은 프로젝트 단위로 누적된 미처리 시그널을 보여주고, 브리프는 당일 생성분을 봅니다.

`filters`는 당일 시그널에 실제 있는 type으로 구성합니다. `key`가 `all`인 항목을 맨 앞에 고정하고 전체 개수를 담은 뒤,
나머지 type 칩을 이어 붙입니다. 각 type 칩의 `key`는 signal type(`change`, `decision`, `risk`, `question`)이고
`count`는 그 type의 당일 시그널 수입니다. 개수가 0인 type은 칩을 만들지 않습니다.
`label`은 `type`의 첫 글자만 대문자로 바꾼 값입니다(위 Signal
카테고리 표시 표와 같음). type 칩 순서는 `label` 글자수가 적은 것부터이고 글자수가 같으면 `label` 알파벳순입니다.

`signal_summary.summary`는 시그널 요약 헤더 아래 문단(해당 프로젝트 이슈와 진행상황 요약)입니다. 그날 시그널을 한
문단으로 요약한 민수 산출물이고 `signal_digests`에서 읽습니다. 민수가 아직 만들지 않았으면 `null`입니다. 요약 생산 기능이
준비되기 전에는 같은 backing 계약을 따르는 fixture가 채웁니다(ADR-0011, ADR-0012).

문단은 `items`와 같은 하루 범위(생성 시각)로 조회하므로 문단과 목록의 기준일이 항상 같습니다. 같은 범위에 문단이
여러 건이면 가장 최근 것을 반환합니다.
화면의 "시그널 요약 · N" 헤더 숫자는 `filters`에서 `key`가 `all`인 항목의
`count`로 렌더링합니다(필터 선택과 무관하게 유지).

`items`는 당일 시그널의 최신순(생성 시각 내림차순, 같으면 id 내림차순) 첫 페이지이고 기본 20개입니다. 클라이언트는
첫 페이지에서 최신 3개만 노출하고, 더보기 시 나머지 항목을 펼쳐 보여줍니다. 이후 20개를 초과하면 `next_cursor`로 다음
페이지를 조회해 무한 스크롤을 이어갑니다. 최신 3개만 노출하는 것은 화면 정책이며, 서버는 전체 첫 페이지를 반환합니다.
change도 다른 type과 똑같이 포함합니다. `next_cursor`는 다음 페이지를 여는 커서 문자열이고 다음 페이지가 없으면
`null`입니다. 커서는 서버만 해석하므로, 클라이언트는 내용을 해석하지 않고 하위 엔드포인트에 그대로 되돌려줍니다.

`project.progress`는 작업 상태를 기준으로 계산합니다. `projects.progress`에 저장된 값은
사용하지 않으며, 조회할 때마다 다시 계산합니다. 따라서 작업 상태가 바뀌면 다음 조회부터 바로 반영됩니다.

진행률은 cancelled를 제외한 작업을 기준으로 done 비율을 계산한 0~100 정수 퍼센트입니다. 정수 나눗셈을 사용하므로
소수점은 버리며, 100은 cancelled를 제외한 모든 작업이 done일 때만 반환합니다. 소프트 삭제된 작업은 제외하고,
작업이 없으면 0을 반환합니다.

cancelled를 분모에서 제외하는 기준은 기획이 확정했습니다. 소수점을 버리는 방식만 아직 확정되지 않아 서버 구현에서
결정한 정책이며(ADR-0013), 추후 기획 확정에 따라 변경될 수 있습니다.

`priorities`의 원천은 작업입니다. `title`은 작업 제목이고, `task_id`로 작업 상세로
이동할 수 있습니다. 정렬은 `priority`가 높은 순(high, medium, low)이고, 같으면 생성이 오래된 순입니다(생성 시각
오름차순, 생성 시각까지 같으면 id 오름차순으로 순서를 고정합니다). 저장된 값이 web에서 사용하는 urgent이면 high와
같은 순위로 정렬합니다. 상위 4개까지만 담습니다. 화면의 "현재 우선순위 · N" 헤더 숫자는 배열 길이로 계산하며,
배열 길이와 항상 같은 값이라 별도 개수 필드를 두지 않습니다. 후보는 진행 중인 todo와 in_progress 상태의
작업이고 backlog와 done, cancelled는 제외합니다.

### GET /api/mobile/projects/{projectId}/brief/signal-summary

브리프 시그널 요약의 필터 전환과 더보기에 사용합니다. 커서 기반 페이지네이션을 사용하며, 정렬은 최신순입니다(생성
시각 내림차순, 같으면 id 내림차순).

더보기는 접힌 상태의 3개 프리뷰를 목록으로 확장하는 동작입니다. 화면설계서의 "드롭다운에서 페이지네이션"은 전체를
한 번에 펼치는 것이 아니라, 목록을 연 뒤 내부 스크롤로 다음 페이지를 이어 조회하는 무한 스크롤을 의미합니다.

브리프 본체가 첫 페이지를 이미 반환하므로 처음 더보기를 열 때 다시 요청하지 않습니다.
필터를 바꾸면 `cursor` 없이 해당 필터의 첫 페이지를 요청합니다. `limit`을 생략하면 기본 20개입니다. 이후 목록 끝까지 스크롤하면 직전 응답의
`next_cursor`를 `cursor`로 전달해 다음 페이지를 조회하고, 반환된 항목을 기존 목록 뒤에 추가합니다. `next_cursor`가
`null`이면 더 조회할 데이터가 없습니다.

`limit`은 화면에서 한 번에 보여줄 기본 페이지 크기를 결정하는 값이고, 최대 페이지 크기(50)는 서버 보호를 위한
상한입니다. 목적이 서로 다르므로 별도로 관리하며, 클라이언트가 상한을 초과해 요청하면 서버는 최대 페이지 크기까지만
반환합니다.

#### Query

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `filter` | 아니오 | `all` 또는 signal type(`change`, `decision`, `risk`, `question`). 기본값은 `all` |
| `cursor` | 아니오 | 이전 응답의 `next_cursor`. 없으면 첫 페이지 |
| `limit` | 아니오 | 페이지 크기. 없거나 0이면 기본 20, 상한 50(넘기면 상한으로 줄임). 음수는 `COMMON_VALIDATION_FAILED` |

정렬과 항목 구성은 브리프 본체의 `signal_summary.items`와 같습니다. `filter`는 임의 문자열을 허용하며 `all`이나 빈 값이면
전체를, 그 외 값은 해당 type을 조회합니다. 알려지지 않은 type은 매칭이 없어 빈 목록으로 응답하고 400을 내지
않습니다. 형식이 잘못된 `cursor`와 음수 `limit`은 `COMMON_VALIDATION_FAILED`(400)로 응답합니다. 커서가 마지막으로 본 항목의 생성 시각과
id를 기준으로 하기 때문에, 페이지 사이에 시그널이 처리되어도 다음 페이지의 위치가 밀리지 않습니다.

## 작업

### GET /api/mobile/projects/{projectId}/tasks

프로젝트 작업 보드를 조회합니다.

보드는 `in_progress`, `todo`, `done`, `cancelled`, `backlog` 순서로 다섯 개의 상태 그룹을 노출합니다. 다섯 그룹은 작업이 없어도 항상 포함하며 그때 tasks는 빈 배열입니다. 각 그룹의 count는 tasks 배열 길이와 같습니다. priority는 low, medium, high로 반환하고, 저장된 값이 레거시 전용인 urgent이면 high로 반환합니다(2026-07-06 가결정). material_count는 작업에 연결된 소스 수이고, 연결이 없으면 0입니다. 웹에서 만든 작업은 업무 역할이 없어 role을 null로 반환합니다(레거시와 공유하는 tasks에서 role은 nullable이고, 모바일 생성 API는 role을 필수로 받습니다).

### POST /api/mobile/projects/{projectId}/tasks

일반 작업을 생성합니다.

생성된 작업은 `todo`로 시작합니다. 생성 제목은 빈 문자열이거나 공백만으로 구성될 수 없습니다. 그 외 입력 검증은 OpenAPI의 `CreateTaskRequest`를 따릅니다.
입력의 `urgent`는 그대로 저장하고 응답에서는 `high`로 표시합니다. 담당자·설명·완료 기준은
생성 입력에 포함하지 않고 생성 후 수정합니다.

### GET /api/mobile/tasks/{taskId}

작업 상세 화면에 필요한 정보를 조회합니다.

`draft_status`는 민수가 이 작업의 제목을 더 손볼 것이 남았는지를 알립니다. 값과 보장은
convert-to-task 응답과 같습니다. 앱은 `generating`이면 나중에 다시 조회해 종료를 확인합니다.
`convert-to-task`를 거치지 않은 작업은 항상 `ready`입니다.

다만 이 조회는 담당자·연결된 소스를 함께 구성하는 무거운 경로라 짧은 간격의 폴링에는 적합하지 않습니다.
폴링 부담이 문제가 되면 경량 상태 조회 엔드포인트나 재조회 간격 힌트를 후속으로 검토합니다.

`materials[].source_url`은 연결된 소스에서 원본 문서로 이동할 때 사용합니다.

`materials`는 `entity_relations`(task ↔ source_ref)에서 연결을 찾고 `source_refs`에서 원본을 조회해 구성합니다.
연결된 소스가 없으면 `[]`이고, `material_count`는 `0`입니다.

`id`는 source_ref의 식별자입니다. `source`는 원본 출처이며 화면 라벨은 앱이 생성합니다(위 Material 참고).
`occurred_at`은 원본이 생성된 시각입니다. `summary`는 source_ref의 snippet이고, snippet이 없으면 본문(text)을
대신 사용합니다. 원본에 값이 없으면 `title`, `summary`, `occurred_at`,
`source_url`은 `null`로 반환합니다. 표시 순서는 연결이 생성된 시각의 내림차순입니다.

`open_questions`와 `next_action`은 민수 산출물입니다. `open_questions[]`는 `task_open_questions`에서, `next_action`은
`tasks.next_action`에서 읽습니다. 민수가 만든 질문이 없으면 `[]`이고, 다음 행동을 아직 만들지 않았으면 `null`입니다.
`open_questions[].id`는 저장된 질문 행의 식별자입니다. 표시 순서는 생산자가 준 순서이고, 순서 값이 겹치면 `id` 순으로
고정합니다.

두 값은 생산 단계에서 공백을 포함해 각각 50자, 100자 이하로 만들고,
서버는 값을 생성하거나 자르지 않고 저장된 값을 그대로 반환합니다. 해당 산출물의 생산 기능이 준비되기 전에는 같은 backing 계약을
따르는 fixture가 채웁니다(ADR-0011). 작업 수정 API는 두 값을 요청에 받지 않고 그대로 유지합니다.

담당자가 지정되지 않았으면 `assignee`는 `null`, 설명을 아직 작성하지 않았으면 `purpose`는 `null`입니다. 웹에서 만든 작업은 업무 역할이 없어 `role`도 `null`입니다. 담당자는 저장된 `assignee_id`를 기준으로 조회합니다. 현재 민수 draft 생성 결과는 title, role, priority만 갱신하며 담당자를 자동 지정하지 않습니다. `assignee.avatar_url`은 담당자의 구글 계정 프로필 이미지이고, 없으면 `null`입니다.
완료 기준이 없으면 `checklist`는 `completed_count` 0, `total_count` 0, `items` 빈 배열입니다.
`status`는 저장된 5종(backlog, todo, in_progress, done, cancelled)을 그대로 반환하고(상세 상태 칩이 5종 노출),
`priority`는 보드와 같이 저장된 urgent를 high로 반환합니다. `purpose`는 레거시 `tasks.description`에 매핑됩니다.

설명·소스·질문·다음 행동의 JSON 키는 각각 `purpose`, `materials`, `open_questions`, `next_action`입니다.
`materials`는 작업에 직접 연결된 소스이며, 메모리까지 포함하는 웹의 연결된 컨텍스트와는 다릅니다.
설명은 최대 300자이며, 모바일 작업 생성·수정 요청의 업무 역할은 필수입니다.

### PATCH /api/mobile/tasks/{taskId}

작업 수정 화면에서 편집한 필드를 저장합니다. 요청은 리소스 전체가 아니라 편집 가능한 필드 전체(title, role, assignee_id, priority, status, purpose, checklist_items)를 담고, 서버가 관리하는 필드(materials, open_questions, next_action)는 요청에 없어 그대로 유지합니다. title, role, priority, status는 항상 채워 보냅니다. title은 생성과 달리 빈 문자열을 허용하고, title을 빈 문자열로 보내면 상세 화면이 '새 작업'으로 표시합니다.

- `assignee_id`·`purpose`를 생략하거나 `null`로 보내면 기존 값을 지웁니다. `purpose`는 빈 문자열도 허용합니다.
- `checklist_items`는 최종 목록으로 전체 교체합니다. 생략·`null`은 전체 삭제입니다. 기존 항목은
  상세에서 받은 `id`로 제목·완료 상태를 갱신하고, `id` 없는 항목은 생성하며 목록에서 빠진 항목은 삭제합니다.
  `completed` 생략은 `false`입니다. 없는 `id`는 `TASK_CHECKLIST_ITEM_NOT_FOUND`, 중복 `id`는
  `COMMON_VALIDATION_FAILED`입니다. 완료 기준 제목은 빈 문자열이거나 공백만으로 구성될 수 없습니다.
- 상태의 허용 값·필수 필드·개수·글자 수 제한은 OpenAPI의 `UpdateTaskRequest`·`ChecklistItemRequest`를
  따릅니다. 상세 화면의 즉시 토글은 별도 엔드포인트를 사용합니다.
- 선택 버전과 낙관적 잠금으로 충돌을 검사합니다. 생략 시 보호 범위와 재조회 절차는
  [태스크·마일스톤 수정 충돌](api-response-error-codes.md#태스크마일스톤-수정-충돌)을 따릅니다.

MCP의 부분 수정은 생략한 필드를 유지하므로 이 API와 다릅니다
([MCP 생략·삭제 계약](mcp-write-tools.md#생략과-삭제)).

저장만 하고 본문은 반환하지 않습니다. 쓰기와 읽기의 역할을 분리하기 위해서입니다. 저장 후 필요한 최신 상태는 작업 상세 조회(`GET /api/mobile/tasks/{taskId}`)로 다시 읽습니다. 새로 추가한 완료 기준의 서버 id도 이 조회에서 확인합니다.

### PATCH /api/mobile/tasks/{taskId}/checklist-items/{itemId}

작업 완료 기준 항목의 완료 상태를 변경합니다. 항목은 지정한 작업에 속해야 하며,
변경 결과와 작업 전체의 완료 수·전체 수를 반환합니다.

## Push 설치

### PUT /api/me/push-devices/{firebaseInstallationId}

Android 기기의 Firebase Installation ID(FID)와 FCM registration token을 등록하거나 갱신합니다.

같은 FID의 재요청은 token을 갱신하고 활성화합니다. 다른 사용자에게 귀속된 FID면 현재 인증 사용자에게
소유권을 원자적으로 이전합니다. 동일한 활성 FCM token이 다른 FID에 연결돼 있으면 이전 연결을
비활성화합니다.

생성과 갱신 모두 본문 없이 `204 No Content`를 반환합니다.

### DELETE /api/me/push-devices/{firebaseInstallationId}

현재 인증 사용자가 소유한 설치를 비활성화합니다. 로그아웃과 별도 endpoint이며, 앱은 로그아웃 직전에
호출합니다.

자기 소유 설치만 비활성화하고, 이미 비활성화됐거나 없는 설치도 `204 No Content`로 멱등 처리합니다.
다른 사용자가 소유한 활성 설치는 해제하지 않습니다. 물리 삭제하지 않아 같은 사용자의 재등록과 token
lifecycle을 안전하게 처리합니다.

### FCM push notification 계약

Signal 생성 시 소비 시점 workspace 구성원의 등록된 Android 기기로 FCM push를 발송합니다. 제목과
본문은 저장된 Project와 Signal을 기준으로 서버가 생성합니다.

```text
제목: {프로젝트명}에 새 시그널이 발견되었습니다.
본문: {시그널 제목}
```

data payload:

```json
{
  "notification_type": "signal_created",
  "destination": "signal_detail",
  "signal_id": "signal-uuid",
  "project_id": "project-uuid",
  "workspace_id": "workspace-uuid"
}
```

`notification_type`은 알림이 발생한 이유이고, `destination`은 클릭 시 이동할 화면입니다. 앱은 알림
클릭 시 `signal_id`로 Signal 상세 화면을 엽니다. payload에는 Signal type이 없으므로
상세 조회 후 type을 사용합니다.

서버는 프로젝트명·Signal 제목을 임의로 자르지 않으며 말줄임과 foreground 표시는 앱/OS가 담당합니다.
background/종료 상태의 시스템 표시를 위한 notification과 data payload를 함께 보냅니다.
수신자는 Signal 생성 시점이 아닌 소비 시점의 workspace 구성원 전체이며 역할·생성자 여부로 제외하지
않습니다. 한 사용자의 모든 활성 Android 설치로 보내고, 설치가 없으면 정상적으로 건너뜁니다.

전달은 at-least-once입니다. FCM 성공 뒤 상태 기록 전에 서버가 종료되면 중복 알림이 올 수 있습니다.
매 전송 직전 설치의 활성 상태·소유자를 다시 확인해 계정 이전 시 이전 사용자의 대기 알림을 취소합니다.
동일 사용자의 token 갱신은 최신 token으로 재시도합니다. 등록 경합의 잔여 제약 위반은
`409 COMMON_CONFLICT`입니다. 운영 한계와 복구는 [운영 준비 대장](../prod-readiness-ledger.md#signal-push-운영)을 따릅니다.
