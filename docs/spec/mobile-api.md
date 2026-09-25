# 모바일 API 명세

이 문서는 모바일 MVP에서 앱이 호출하는 HTTP API 계약만 정리합니다.

## 공통

- Base URL: `/api`
- 모바일 기능 API: `/api/mobile/*`
- 인증 API: `/api/auth/*`
- API version header: `API-Version: 1`
- 인증 header: `Authorization: Bearer {access_token}`
- Content-Type: `application/json`
- 필드 표기: `snake_case`
- 성공 응답: 별도 wrapper 없이 응답 DTO 그대로 반환

인증 API 중 로그인과 토큰 갱신은 `Authorization` 헤더가 필요하지 않습니다.

### 에러 응답

```json
{
  "error": {
    "code": "COMMON_VALIDATION_FAILED",
    "message": "요청 값이 올바르지 않습니다."
  }
}
```

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

| 이름 | 값 |
| --- | --- |
| `group_key` | `backlog`, `todo`, `in_progress`, `done`, `cancelled` |
| `priority` | `high`, `medium`, `low` |
| `role` | `pm`, `design`, `backend`, `frontend` (하나만 선택. android, qa는 2026-07-08 기획 확정으로 폐기. 응답에서는 웹에서 만든 태스크가 미지정이면 `null`) |

### Membership

| 이름 | 값 |
| --- | --- |
| `role` | `owner`, `admin`, `member` |

### Material

| 이름 | 값 |
| --- | --- |
| `source` | `slack`, `github`, `figma`, `notion`, `file` |

`source`는 관련자료 원본의 출처입니다. Signal 근거의 `evidence.source`와 같은 source_ref의 출처이므로 값 집합도
동일합니다. 화면에 표시하는 출처 라벨과 아이콘은 앱이 `source`에서 생성합니다.

관련자료 화면은 figma, slack, github, file을 표시합니다. 다만 서버는 source_ref의 출처를 그대로 반환하므로, 앱은
목록에 없는 값이 오더라도 화면이 깨지지 않도록 처리합니다.

## 인증

### POST /api/auth/google/token

Google ID token을 Momens access/refresh token으로 교환합니다.

#### Request

```json
{
  "id_token": "google-id-token",
  "device": "iPhone 15 Pro"
}
```

#### Response 200

```json
{
  "access_token": "access-token",
  "refresh_token": "refresh-token",
  "token_type": "Bearer",
  "expires_in": 900
}
```

#### Errors

- `AUTH_GOOGLE_TOKEN_INVALID`
- `AUTH_GOOGLE_EMAIL_NOT_VERIFIED`
- `USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY`
- `COMMON_VALIDATION_FAILED`

### POST /api/auth/refresh

Refresh token으로 access/refresh token을 재발급합니다.

#### Request

```json
{
  "refresh_token": "refresh-token"
}
```

#### Response 200

```json
{
  "access_token": "new-access-token",
  "refresh_token": "new-refresh-token",
  "token_type": "Bearer",
  "expires_in": 900
}
```

#### Errors

- `AUTH_REFRESH_TOKEN_INVALID`
- `COMMON_VALIDATION_FAILED`

### POST /api/auth/logout

Refresh token을 폐기합니다.

#### Request

```json
{
  "refresh_token": "refresh-token"
}
```

#### Response 200

```json
{
  "message": "logged out"
}
```

#### Errors

- `AUTH_REFRESH_TOKEN_INVALID`
- `COMMON_VALIDATION_FAILED`

## 모바일 진입

### GET /api/mobile/bootstrap

로그인 후 앱 진입에 필요한 사용자 정보와 프로젝트 목록을 조회합니다.

- `projects`는 접근 가능한 프로젝트를 생성 최신순으로 담습니다. `role`은 소속 workspace 멤버십 role입니다.
- `default_project_id`는 임의의 프로젝트 1개면 충분하다고 기획이 확인했고(2026-07-04), 서버는
  접근 가능한 프로젝트 중 가장 최근에 만든 것을 선택합니다.
- 접근 가능한 프로젝트가 없으면 `200`으로 `default_project_id`는 `null`, `projects`는 빈 배열을
  반환하고, 빈 화면 처리는 앱이 담당합니다(2026-07-04 가결정, 기획 확인 후 확정).

#### Response 200

```json
{
  "me": {
    "id": "0b3f8c1a-2d4e-4f6a-8b9c-1e2d3f4a5b6c",
    "email": "minji@example.com",
    "name": "김민지",
    "avatar_url": null
  },
  "default_project_id": "30d9e9fe-f43b-4097-a88e-dc19f0a5b025",
  "projects": [
    {
      "id": "30d9e9fe-f43b-4097-a88e-dc19f0a5b025",
      "name": "Q2 Activation Readiness",
      "role": "member"
    }
  ]
}
```

#### Response 200 (접근 가능한 프로젝트 없음)

```json
{
  "me": {
    "id": "0b3f8c1a-2d4e-4f6a-8b9c-1e2d3f4a5b6c",
    "email": "minji@example.com",
    "name": "김민지",
    "avatar_url": null
  },
  "default_project_id": null,
  "projects": []
}
```

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `USER_NOT_FOUND` (유효한 토큰이지만 사용자가 삭제된 경우. `GET /api/me`와 같은 동작)

## 프로젝트

### GET /api/mobile/projects/{projectId}/members

태스크 담당자 선택에 사용할 프로젝트 멤버 목록을 조회합니다.

- `members`의 범위는 project가 속한 workspace의 멤버십입니다. 별도 project 멤버 테이블을 두지
  않는 권한 요구사항과 같은 기준입니다.
- 정렬은 이름 오름차순이고, 같은 이름은 id 오름차순으로 순서를 고정합니다(2026-07-04 가결정).
- `query`는 앞뒤 공백을 지운 뒤 이름 부분 일치로 거르고 대소문자를 무시합니다. 없거나 공백이면
  전체 멤버를 반환합니다(2026-07-04 가결정).
- `avatar_url`은 값이 없어도 `null`로 항상 포함합니다(bootstrap과 동일).
- 레거시 `GET /workspaces/:id/members`와 달리 email, role, 시각을 내리지 않습니다. 담당자 선택
  bottom sheet가 쓰는 값(id, 이름, 아바타)만 담는 신규 계약입니다(의도된 차이).

#### Query

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `query` | 아니오 | 이름 검색어 |

#### Response 200

```json
{
  "members": [
    {
      "id": "b9b1...e7",
      "name": "김민지",
      "avatar_url": null
    }
  ]
}
```

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND` (project가 없거나 삭제된 경우)
- `AUTH_FORBIDDEN` (project는 있지만 요청 사용자가 소속 workspace의 멤버가 아닌 경우)

## 시그널

### GET /api/mobile/projects/{projectId}/signals

프로젝트의 시그널 목록을 조회합니다.

MVP에서는 아직 처리되지 않은 시그널만 반환합니다. `convert-to-task` 또는 `dismiss`로 처리된 시그널을
다시 보는 inbox/필터 흐름은 MVP 이후로 둡니다.

정렬은 최신 Signal이 먼저 오도록 생성 시각 내림차순을 기본으로 합니다. 생성 시각이 같으면 id
내림차순으로 순서를 고정합니다. 수십 건 이상 누적될 때의 pagination/cursor 계약은 MVP 이후 확장으로
둡니다.

카드의 `Needs action`, `Needs review`, `Needs decision` 라벨은 응답의 `type`에서 앱이 파생합니다. 서버가
별도 처리 상태 필드를 내려주지 않습니다.

`impact`는 프로젝트에 미칠 영향 요약이며 목록 카드의 보조 문구로도 사용합니다. `impact`는 worker가
Signal과 함께 생산하고, worker가 준비되지 않은 MVP 환경에서는 같은 backing 계약의 fixture가 채웁니다.
`minsu_suggestion`은 민수 산출물이며, 민수(서버 내 모듈로 구현 예정)가 구현되기 전에는 목으로
처리합니다. 서버는 근거 없는 문구를 임의 생성하지 않습니다.

응답 항목에서는 `project_id`를 생략하지만 Signal backing의 `project_id`는 유지합니다. 서버가 경로의 프로젝트로
목록을 필터링하고, 원탭 전환 시 task를 어느 프로젝트에 만들지 결정하는 내부 귀속 정보이기 때문입니다.

#### Response 200

```json
{
  "title": "오늘 확인해야 할 시그널",
  "description": "프로젝트의 의사결정에 영향을 줄 수 있는 변화입니다.",
  "signals": [
    {
      "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
      "type": "risk",
      "title": "Android 13+ 권한 요청 플로우에서 이탈 가능성 발견",
      "impact": "MVP 완료율과 온보딩 품질에 영향을 줄 수 있습니다.",
      "minsu_suggestion": "내용이 들어갈 공간입니다"
    }
  ]
}
```

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND`
- `AUTH_FORBIDDEN`

### GET /api/mobile/signals/{signalId}

시그널 상세 bottom sheet에 필요한 정보를 조회합니다.

상세 상단의 `Needs action` 등 라벨은 Signal type 기반 표시 라벨이며, 처리 상태가 아닙니다.

#### Response 200

```json
{
  "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
  "type": "risk",
  "title": "Android 13+ 권한 요청 플로우에서 이탈 가능성 발견",
  "impact": "MVP 완료율과 온보딩 품질에 영향을 줄 수 있습니다.",
  "evidence": [
    {
      "source_ref_id": "source-ref-uuid",
      "source": "figma",
      "occurred_at": "2026-06-28T09:48:00+09:00",
      "details": {
        "target": "권한 요청 화면",
        "change": "권한 요청 단계 이탈률 증가",
        "impact": "회원가입 완료율 저하 가능"
      },
      "source_url": "https://..."
    }
  ],
  "minsu_suggestion": "권한 요청 시점을 다시 검토해보세요."
}
```

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

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `SIGNAL_NOT_FOUND`
- `AUTH_FORBIDDEN`

### POST /api/mobile/signals/{signalId}/actions/convert-to-task

시그널을 원탭으로 태스크에 등록합니다. 요청 body는 없습니다.

서버는 태스크 등록 시점에 민수가 생성하는 task draft(`title`, `role`, `priority`)를
사용합니다. 민수는 서버 내 `minsu` 모듈로 구현합니다. 비활성·설정 무효·입력 부족·외부
실패·출력 무효 시 고정 draft(`title`은 15자로 제한한 Signal title, `role`은 `pm`,
`priority`는 `medium`)를 사용합니다. 생성된 title은 공백 포함 최대 15자이며, draft는 Signal
backing에 저장하지 않습니다.

클라이언트는 title·role·priority를 선택하거나 전송하지 않습니다.

`draft_status`는 민수가 이 태스크의 제목을 더 손볼 것이 남았는지를 알립니다. 값은 `generating`과
`ready` 둘뿐이며, 생성이 실패했는지 사용자가 먼저 편집했는지는 구분하지 않고 모두 `ready`입니다.
서버가 보장하는 것은 셋입니다.

- `draft_status`는 반드시 `ready`에 도달합니다. `generating`에 갇히지 않습니다.
- **정상 종료에서는** `ready`와 함께 돌려준 `title`이 최종 값입니다. 여기서 재조회를 멈춰도 갱신을
  놓치지 않습니다. 예외는 생성이 deadline을 넘겨 닫힌 경우 하나입니다. 이때는 뒤늦게 커밋된 반영으로
  `title`이 한 번 더 바뀔 수 있습니다. deadline 발동 자체가 서버 경보 대상인 이상 상황이며, 사용자에게는
  "다음 상세 진입에서 제목이 달라 보임" 수준입니다.
- `generating` 동안에도 `title`은 항상 유효한 값입니다. 로딩을 표시하지 않고 그대로 렌더해도 됩니다.

`generating`을 어떻게 표시할지와 재조회 간격은 앱이 정합니다. 결과를 확인하는 경로는 이 응답과
태스크 상세 둘뿐이고, **보드·목록에는 `draft_status`가 없습니다.** 보드 카드의 title은 사용자가
상세에 들어가지 않으면 이전 값인 채로 있다가 다음 목록 조회에서 조용히 바뀝니다.

#### Response 201

```json
{
  "task": {
    "id": "new-task-uuid",
    "title": "권한 요청 플로우 점검",
    "status": "todo",
    "draft_status": "generating"
  },
  "signal": {
    "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
    "action": "convert_to_task"
  }
}
```

#### Response 200 (이미 처리된 신호 재요청)

같은 신호에 같은 액션을 재요청하면 새 task를 만들지 않고 기존 결과를 그대로 반환합니다(멱등).

```json
{
  "task": {
    "id": "existing-task-uuid",
    "title": "권한 요청 플로우 점검",
    "status": "todo",
    "draft_status": "ready"
  },
  "signal": {
    "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
    "action": "convert_to_task"
  }
}
```

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `SIGNAL_NOT_FOUND`
- `SIGNAL_INVALID_STATE`
- `AUTH_FORBIDDEN`

### POST /api/mobile/signals/{signalId}/actions/dismiss

제안된 시그널을 MVP 흐름에서 수용하지 않고 목록에서 삭제 처리합니다. 모바일 화면의 버튼 라벨은 `삭제`지만
서버 action 이름은 `dismiss`입니다. 이 액션은 물리 삭제가 아니고 시그널이 잘못됐다고 확정하는 것도 아니라,
사용자가 현재 시그널을 task로 전환하지 않겠다는 처리 기록입니다. 삭제 처리한 시그널을 다시 보는 inbox
흐름은 MVP 이후로 둡니다.

#### Response 200

```json
{
  "signal": {
    "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
    "action": "dismiss"
  }
}
```

#### Response 200 (이미 같은 액션으로 처리된 신호 재요청)

같은 신호에 dismiss를 재요청하면 같은 결과를 멱등하게 반환합니다.

```json
{
  "signal": {
    "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
    "action": "dismiss"
  }
}
```

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `SIGNAL_NOT_FOUND`
- `SIGNAL_INVALID_STATE`
- `AUTH_FORBIDDEN`

`SIGNAL_INVALID_STATE`(409)는 이미 다른 액션으로 처리된 Signal에 요청하거나, 현재 상태에서 요청한 액션을
수행할 수 없는 경우에 반환합니다. 같은 액션 재요청은 위 `200` 멱등 응답으로 처리합니다.

## 브리프

### GET /api/mobile/projects/{projectId}/brief

프로젝트 브리프 화면의 초기 로드에 필요한 정보를 조회합니다. 쿼리 파라미터는 없습니다. 시그널 요약의
필터 전환과 더보기 페이지 이동은 아래 하위 엔드포인트가 담당합니다.

#### Response 200

```json
{
  "project": {
    "id": "30d9e9fe-f43b-4097-a88e-dc19f0a5b025",
    "name": "Q2 Activation Readiness",
    "target_date": "2026-06-30",
    "progress": 64,
    "summary": "목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다."
  },
  "signal_summary": {
    "summary": "Android 권한 요청 이슈가 발견되었으며, 소셜 로그인은 MVP 범위에서 제외되었습니다. 이메일 회원가입과 온보딩 이탈 개선이 우선적으로 필요합니다.",
    "filters": [
      { "key": "all", "label": "All", "count": 6 },
      { "key": "risk", "label": "Risk", "count": 1 },
      { "key": "change", "label": "Change", "count": 1 },
      { "key": "decision", "label": "Decision", "count": 2 },
      { "key": "question", "label": "Question", "count": 2 }
    ],
    "items": [
      {
        "id": "5c1a2b34-56d7-4e89-9f01-234a5b6c7d8e",
        "type": "change",
        "title": "권한 요청 반복 문의"
      },
      {
        "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
        "type": "decision",
        "title": "소셜 로그인은 MVP 범위에서 제외"
      },
      {
        "id": "3b9e0d12-78f4-4a56-8c01-9d2e3f4a5b6c",
        "type": "risk",
        "title": "Android 13+ 권한 요청 플로우 이탈 가능성"
      }
    ],
    "next_cursor": "MjAyNi0wNy0wM1QwMDowMDowMFp8M2I5ZTBkMTItNzhmNC00YTU2LThjMDEtOWQyZTNmNGE1YjZj"
  },
  "priorities": [
    {
      "rank": 1,
      "title": "이메일 회원가입 완료율 개선",
      "task_id": "27afd507-9c7f-4f0d-a2be-fcdab2477b19"
    },
    {
      "rank": 2,
      "title": "Android 13+ 권한 요청 플로우 정비",
      "task_id": "4c8e1f23-a567-4b89-9c01-2d3e4f5a6b7c"
    },
    {
      "rank": 3,
      "title": "온보딩 이탈 구간 계측 추가",
      "task_id": "9d0a2b34-c678-4d90-8e12-3f4a5b6c7d8e"
    },
    {
      "rank": 4,
      "title": "소셜 로그인 제외 범위 QA",
      "task_id": "1e2f3a45-b789-4c01-9d23-4a5b6c7d8e9f"
    }
  ]
}
```

브리프는 오늘의 브리프라, 시그널 요약은 그날(당일) 생성된 시그널을 처리 여부와 무관하게 집계합니다(2026-07-10 기획
확정). 태스크 전환이나 dismiss와 상관없이 그날 온 시그널을 유지하고, 소프트 삭제된 시그널은 제외합니다. 하루 경계는
Asia/Seoul 기준이라, `created_at`(UTC 저장)을 KST 하루로 잘라 그 범위의 시그널을 담습니다. 이 집계 기준은 시그널
탭(처리 대기)과 다릅니다. 시그널 탭은 프로젝트 단위로 누적된 미처리 시그널을 보여주고, 브리프는 당일 생성분을 봅니다.

`filters`는 당일 시그널에 실제 있는 type으로 구성합니다. `key`가 `all`인 항목을 맨 앞에 고정하고 전체 개수를 담은 뒤,
나머지 type 칩을 이어 붙입니다. 각 type 칩의 `key`는 signal type(`change`, `decision`, `risk`, `question`)이고
`count`는 그 type의 당일 시그널 수입니다. 개수가 0인 type은 칩을 만들지 않습니다. type 목록을 서버가 고정하지 않아
민수가 새 type을 만들면 칩이 자동으로 늘어납니다. `label`은 `type`의 첫 글자만 대문자로 바꾼 값입니다(위 Signal
카테고리 표시 표와 같음). type 칩 순서는 `label` 글자수가 적은 것부터이고 글자수가 같으면 `label` 알파벳순입니다.

`signal_summary.summary`는 시그널 요약 헤더 아래 문단(해당 프로젝트 이슈와 진행상황 요약)입니다. 그날 신호를 한
문단으로 요약한 민수 산출물이고 `signal_digests`에서 읽습니다. 민수가 아직 만들지 않았으면 `null`입니다. 민수가
구현되기 전에는 같은 backing 계약을 따르는 fixture가 채웁니다(ADR-0011, ADR-0012).

문단은 `items`와 같은 하루 범위(생성 시각)로 조회하므로 문단과 목록의 기준일이 항상 같습니다. 같은 범위에 문단이
여러 건이면 가장 최근 것을 반환합니다.
2026-07-10 화면설계서는 이 문단을 시그널 요약이라 부르고 리뷰 요약이라는 개념이나 헤더는 따로 없어서, 별도
`review_summary` 객체를 두지 않습니다. 화면의 "시그널 요약 · N" 헤더 숫자는 `filters`에서 `key`가 `all`인 항목의
`count`로 렌더링합니다(필터 선택과 무관하게 유지).

`items`는 당일 시그널의 최신순(생성 시각 내림차순, 같으면 id 내림차순) 첫 페이지이고 기본 20개입니다. 클라이언트는
첫 페이지에서 최신 3개만 노출하고, 더보기 시 나머지 항목을 펼쳐 보여줍니다. 이후 20개를 초과하면 `next_cursor`로 다음
페이지를 조회해 무한 스크롤을 이어갑니다. 최신 3개만 노출하는 것은 화면 정책이며, 서버는 전체 첫 페이지를 반환합니다.
change도 다른 type과 똑같이 포함합니다. `next_cursor`는 다음 페이지를 여는 커서 문자열이고 다음 페이지가 없으면
`null`입니다. 커서는 서버만 해석하므로, 클라이언트는 내용을 해석하지 않고 하위 엔드포인트에 그대로 되돌려줍니다.

`project.progress`는 태스크 상태를 기준으로 계산합니다(2026-06-24 기획 확정). `projects.progress`에 저장된 값은
사용하지 않으며, 조회할 때마다 다시 계산합니다. 따라서 태스크 상태가 바뀌면 다음 조회부터 바로 반영됩니다.

진행률은 cancelled를 제외한 태스크를 기준으로 done 비율을 계산한 0~100 정수 퍼센트입니다. 정수 나눗셈을 사용하므로
소수점은 버리며, 100은 cancelled를 제외한 모든 태스크가 done일 때만 반환합니다. 소프트 삭제된 태스크는 제외하고,
태스크가 없으면 0을 반환합니다.

cancelled를 분모에서 제외하는 기준은 기획이 확정했습니다. 소수점을 버리는 방식만 아직 확정되지 않아 서버 구현에서
결정한 정책이며(ADR-0013), 추후 기획 확정에 따라 변경될 수 있습니다.

`priorities`의 원천은 태스크입니다(2026-07-10 기획 확정). `title`은 태스크 제목이고, `task_id`로 태스크 상세로
이동할 수 있습니다. 정렬은 `priority`가 높은 순(high, medium, low)이고, 같으면 생성이 오래된 순입니다(생성 시각
오름차순, 생성 시각까지 같으면 id 오름차순으로 순서를 고정합니다). 저장된 값이 web에서 사용하는 urgent이면 high와
같은 순위로 정렬합니다. 상위 4개까지만 담습니다. 화면의 "현재 우선순위 · N" 헤더 숫자는 배열 길이로 계산하며,
배열 길이와 항상 같은 값이라 별도 개수 필드를 두지 않습니다. 후보는 진행 중인 todo와 in_progress 상태의
태스크이고 backlog와 done, cancelled는 제외합니다(2026-07-10 기획 확정).

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND`
- `AUTH_FORBIDDEN`

### GET /api/mobile/projects/{projectId}/brief/signal-summary

브리프 시그널 요약의 필터 전환과 더보기에 사용합니다. 커서 기반 페이지네이션을 사용하며, 정렬은 최신순입니다(생성
시각 내림차순, 같으면 id 내림차순, 2026-07-10 확정).

더보기는 접힌 상태의 3개 프리뷰를 목록으로 확장하는 동작입니다. 화면설계서의 "드롭다운에서 페이지네이션"은 전체를
한 번에 펼치는 것이 아니라, 목록을 연 뒤 내부 스크롤로 다음 페이지를 이어 조회하는 무한 스크롤을 의미합니다.

첫 요청은 `limit`을 생략해 기본 페이지 크기(20)만큼 조회합니다. 이후 목록 끝까지 스크롤하면 직전 응답의
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

#### Response 200

```json
{
  "items": [
    {
      "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1",
      "type": "decision",
      "title": "소셜 로그인은 MVP 범위에서 제외"
    }
  ],
  "next_cursor": null
}
```

정렬과 항목 구성은 브리프 본체의 `signal_summary.items`와 같습니다. `filter`는 열린 어휘라 `all`이나 빈 값이면
전체를, 그 외 값은 해당 type을 조회합니다. 알려지지 않은 type은 매칭이 없어 빈 목록으로 응답하고 400을 내지
않습니다. type 어휘를 서버가 고정하지 않아 새 type이 코드 변경 없이 흐르게 하려는 선택입니다. 형식이 잘못된
`cursor`와 음수 `limit`은 `COMMON_VALIDATION_FAILED`(400)로 응답합니다. 커서가 마지막으로 본 항목의 생성 시각과
id를 기준으로 하기 때문에, 페이지 사이에 시그널이 처리되어도 다음 페이지의 위치가 밀리지 않습니다.

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND`
- `COMMON_VALIDATION_FAILED`
- `AUTH_FORBIDDEN`

## 태스크

### GET /api/mobile/projects/{projectId}/tasks

프로젝트 태스크 보드를 조회합니다.

#### Response 200

```json
{
  "title": "프로젝트 태스크",
  "description": "업무를 한눈에 확인하고 상세 내용을 확인하세요.",
  "groups": [
    {
      "group_key": "todo",
      "label": "투두",
      "count": 2,
      "tasks": [
        {
          "id": "27afd507-9c7f-4f0d-a2be-fcdab2477b19",
          "title": "1차 와이어프레임",
          "role": "frontend",
          "priority": "low",
          "material_count": 2
        }
      ]
    },
    { "group_key": "in_progress", "label": "진행중", "count": 2, "tasks": [] },
    { "group_key": "done", "label": "완료", "count": 2, "tasks": [] },
    { "group_key": "backlog", "label": "백로그", "count": 0, "tasks": [] },
    { "group_key": "cancelled", "label": "취소", "count": 0, "tasks": [] }
  ]
}
```

보드는 todo, in_progress, done, backlog, cancelled 다섯 그룹을 순서대로 노출합니다. 태스크 수정 화면이 상태 5종을 모두 편집하므로, backlog나 cancelled로 바꾼 태스크가 보드에서 사라지지 않도록 다섯 그룹을 모두 담습니다(MOM-75). 다섯 그룹은 태스크가 없어도 항상 포함하며 그때 tasks는 빈 배열입니다. priority는 low, medium, high로 반환하고, 저장된 값이 레거시 전용인 urgent이면 high로 반환합니다(2026-07-06 가결정). material_count는 태스크에 연결된 관련 자료 수이고, 연결이 없으면 0입니다. 웹에서 만든 태스크는 역할이 없어 role을 null로 반환합니다(레거시와 공유하는 tasks에서 role은 nullable이고, 모바일 생성 API는 role을 필수로 받습니다).

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND`
- `AUTH_FORBIDDEN`

### POST /api/mobile/projects/{projectId}/tasks

일반 태스크를 생성합니다.

#### Request

```json
{
  "title": "Write",
  "role": "pm",
  "priority": "medium"
}
```

#### Response 201

```json
{
  "task": {
    "id": "new-task-uuid",
    "project_id": "30d9e9fe-f43b-4097-a88e-dc19f0a5b025",
    "title": "Write",
    "role": "pm",
    "priority": "medium",
    "status": "todo"
  }
}
```

title, role, priority 모두 필수입니다(2026-07-06 기획 확정, 2026-07-07 역할은 하나만 선택하는 단일 값으로 재확정). role은 pm, design, backend, frontend 중 하나입니다(2026-07-08 기획 확정으로 android, qa는 폐기하고 역할은 4종만 둡니다). priority는 low, medium, high, urgent 중 하나이며, urgent는 그대로 저장하고 응답에서는 high로 표시합니다. 셋 중 하나라도 비어 있거나 허용하지 않는 값이면 COMMON_VALIDATION_FAILED로 응답합니다. 제목은 공백을 포함해 15자로 제한하며, 넘기면 COMMON_VALIDATION_FAILED로 응답합니다(수정 화면과 같은 태스크 공통 규칙). 생성한 태스크는 todo 그룹에서 시작합니다. role은 레거시 tasks에 없는 신규 속성이라 CHECK 제약을 둔 문자열 컬럼으로 저장합니다.

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`
- `PROJECT_NOT_FOUND`
- `COMMON_VALIDATION_FAILED`
- `AUTH_FORBIDDEN`

### GET /api/mobile/tasks/{taskId}

태스크 상세 화면에 필요한 정보를 조회합니다.

#### Response 200

```json
{
  "id": "27afd507-9c7f-4f0d-a2be-fcdab2477b19",
  "project_id": "30d9e9fe-f43b-4097-a88e-dc19f0a5b025",
  "title": "1차 와이어프레임",
  "status": "todo",
  "role": "pm",
  "assignee": {
    "id": "b9b1...e7",
    "name": "김민지",
    "avatar_url": "https://lh3.googleusercontent.com/a/..."
  },
  "priority": "medium",
  "purpose": "이번 범위에서 확인해야 할 화면 흐름을 정리합니다.",
  "checklist": {
    "completed_count": 2,
    "total_count": 4,
    "items": [
      { "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "title": "어쩌구어쩌구 반영", "completed": true },
      { "id": "5a1b2c3d-4e5f-4061-8273-8495a6b7c8d9", "title": "어쩌구어쩌구 반영", "completed": false }
    ]
  },
  "materials": [
    {
      "id": "9f1c2b3a-4d5e-4f60-8a71-2b3c4d5e6f70",
      "title": "회원가입 에러 메시지 정책 초안",
      "summary": "회원가입의 MVP 완료율과 온보딩 품질에 영향을 줄 수 있습니다.",
      "source": "figma",
      "occurred_at": "2026-06-28T09:48:00+09:00",
      "source_url": "https://..."
    }
  ],
  "open_questions": [
    {
      "id": "3f2a1b4c-5d6e-4f70-8a91-b2c3d4e5f6a7",
      "body": "약한 비밀번호 기준을 얼마나 구체적으로 안내할지 결정 필요"
    }
  ],
  "next_action": "에러 메시지 정책 초안을 검토하고 핵심 문구 다섯 가지를 먼저 확정하세요.",
  "draft_status": "ready"
}
```

`draft_status`는 민수가 이 태스크의 제목을 더 손볼 것이 남았는지를 알립니다. 값과 보장은
convert-to-task 응답과 같습니다. 앱은 `generating`이면 나중에 다시 조회해 종료를 확인합니다.
`convert-to-task`를 거치지 않은 태스크는 항상 `ready`입니다.

다만 이 조회는 담당자·관련자료를 함께 구성하는 무거운 경로라 짧은 간격의 폴링에는 적합하지 않습니다.
폴링 부담이 문제가 되면 경량 상태 조회 엔드포인트나 재조회 간격 힌트를 후속으로 검토합니다.

`materials[].source_url`은 관련자료에서 원본 문서로 이동할 때 사용합니다.

`materials`는 `entity_relations`(task ↔ source_ref)에서 연결을 찾고 `source_refs`에서 원본을 조회해 구성합니다.
연결된 자료가 없으면 `[]`이고, `material_count`는 `0`입니다.

`id`는 source_ref의 식별자입니다. `source`는 원본 출처이며 화면 라벨은 앱이 생성합니다(위 Material 참고).
`occurred_at`은 원본이 생성된 시각입니다. `summary`는 source_ref의 snippet이고, snippet이 없으면 본문(text)을
대신 사용합니다. 이 규칙은 Signal 근거와 동일합니다. 원본에 값이 없으면 `title`, `summary`, `occurred_at`,
`source_url`은 `null`로 반환합니다. 표시 순서는 연결이 생성된 시각의 내림차순입니다.

`open_questions`와 `next_action`은 민수 산출물입니다. `open_questions[]`는 `task_open_questions`에서, `next_action`은
`tasks.next_action`에서 읽습니다. 민수가 만든 질문이 없으면 `[]`이고, 다음행동을 아직 만들지 않았으면 `null`입니다.
`open_questions[].id`는 저장된 질문 행의 식별자입니다. 표시 순서는 생산자가 준 순서이고, 순서 값이 겹치면 `id` 순으로
고정합니다.

두 값은 생산 단계에서 공백을 포함해 각각 50자, 100자 이하로 만들고(2026-07-08 화면설계서 task_002 8번, 9번),
서버는 값을 생성하거나 자르지 않고 저장된 값을 그대로 반환합니다. 민수가 구현되기 전에는 같은 backing 계약을
따르는 fixture가 채웁니다(ADR-0011). 태스크 수정 API는 두 값을 요청에 받지 않고 그대로 유지합니다.

담당자가 지정되지 않았으면 `assignee`는 `null`, 목적을 아직 작성하지 않았으면 `purpose`는 `null`입니다. 웹에서 만든 태스크는 역할이 없어 `role`도 `null`입니다. 태스크 생성 시점에 민수가 담당자를 판단해 지정하는데, 판단에 걸리는 시간 동안에는 담당자가 없어 `assignee`가 `null`로 내려가고, 이후 지정되면 조회에 자동으로 반영됩니다. `assignee.avatar_url`은 담당자의 구글 계정 프로필 이미지이고, 없으면 `null`입니다.
완료기준이 없으면 `checklist`는 `completed_count` 0, `total_count` 0, `items` 빈 배열입니다(2026-07-07 확정).
`status`는 저장된 5종(backlog, todo, in_progress, done, cancelled)을 그대로 반환하고(상세 상태 칩이 5종 노출),
`priority`는 보드와 같이 저장된 urgent를 high로 반환합니다. `purpose`는 레거시 `tasks.description`에 매핑됩니다.

#### Errors

- `TASK_NOT_FOUND`
- `AUTH_FORBIDDEN`

### PATCH /api/mobile/tasks/{taskId}

태스크 수정 화면에서 편집한 필드를 저장합니다. 요청은 리소스 전체가 아니라 편집 가능한 필드 전체(title, role, assignee_id, priority, status, purpose, checklist_items)를 담고, 서버가 관리하는 필드(materials, open_questions, next_action)는 요청에 없어 그대로 유지합니다. title, role, priority, status는 항상 채워 보냅니다. title은 생성과 달리 빈 문자열을 허용하고, title을 빈 문자열로 보내면 상세 화면이 '새 태스크'로 표시합니다.

#### Request

```json
{
  "title": "1차 와이어프레임",
  "role": "pm",
  "assignee_id": "b1c2d3e4-5f60-4711-8a22-99c0ab12cd34",
  "priority": "medium",
  "status": "in_progress",
  "purpose": "이번 범위에서 확인해야 할 화면 흐름을 정리합니다.",
  "checklist_items": [
    { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "title": "어쩌구어쩌구 반영", "completed": true },
    { "title": "새 완료기준", "completed": false }
  ]
}
```

담당자를 비우려면 `assignee_id`를 `null`로 보냅니다. `status`는 backlog, todo, in_progress, done, cancelled 다섯 값 중 하나입니다. `checklist_items`는 완료기준 최종 목록이고, `id`가 있으면 기존 항목의 제목과 완료 상태(`completed`)를 함께 갱신하고, `id`가 없으면 새 항목으로 만들며, 목록에서 빠진 기존 항목은 삭제합니다. `checklist_items[].id`는 선택 필드입니다. 기존 항목은 상세 조회에서 받은 `id`를 그대로 보내고, 새 항목은 `id`를 생략합니다. `completed`를 생략하면 `false`로 처리합니다. `id`가 있는데 기존 항목에 없으면 잘못된 요청으로 보고 `TASK_CHECKLIST_ITEM_NOT_FOUND`로 응답하고, 같은 `id`가 목록에 두 번 오면 `COMMON_VALIDATION_FAILED`로 응답합니다. 완료기준은 0개에서 5개까지 허용하고, 5개를 넘기면 `COMMON_VALIDATION_FAILED`로 응답합니다. 글자 수는 공백을 포함해 태스크 제목 15자, 목적 300자, 완료기준 항목 50자로 제한하며, 넘기면 `COMMON_VALIDATION_FAILED`로 응답합니다. 상세 화면의 즉시 토글은 아래 별도 엔드포인트로 유지합니다. 같은 태스크를 두 요청이 거의 동시에 저장하면, 마지막에 저장한 요청이 먼저 저장된 내용을 덮어씁니다. 동시 수정 충돌을 막는 낙관적 잠금은 지금 두지 않습니다.

#### Response 204

저장만 하고 본문은 반환하지 않습니다. 쓰기와 읽기의 역할을 분리하기 위해서입니다. 저장 후 필요한 최신 상태는 태스크 상세 조회(`GET /api/mobile/tasks/{taskId}`)로 다시 읽습니다. 새로 추가한 완료기준의 서버 id도 이 조회에서 확인합니다.

#### Errors

- `TASK_NOT_FOUND`
- `TASK_CHECKLIST_ITEM_NOT_FOUND`
- `COMMON_VALIDATION_FAILED`
- `AUTH_FORBIDDEN`

### PATCH /api/mobile/tasks/{taskId}/checklist-items/{itemId}

태스크 체크리스트 항목의 완료 상태를 변경합니다.

#### Request

```json
{
  "completed": true
}
```

#### Response 200

```json
{
  "checklist": {
    "completed_count": 2,
    "total_count": 4,
    "item": {
      "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "title": "어쩌구어쩌구 반영",
      "completed": true
    }
  }
}
```

#### Errors

- `TASK_NOT_FOUND`
- `TASK_CHECKLIST_ITEM_NOT_FOUND`
- `COMMON_VALIDATION_FAILED`
- `AUTH_FORBIDDEN`

## Push 설치

### PUT /api/me/push-devices/{firebaseInstallationId}

Android 기기의 Firebase Installation ID(FID)와 FCM registration token을 등록하거나 갱신합니다.

#### Request

```json
{
  "fcm_registration_token": "...",
  "platform": "android"
}
```

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `fcm_registration_token` | 필수 | 공백 문자열 불가 |
| `platform` | 필수 | `android`만 허용 |

같은 FID의 재요청은 token을 갱신하고 활성화합니다. 다른 사용자에게 귀속된 FID면 현재 인증 사용자에게
소유권을 원자적으로 이전합니다. 동일한 활성 FCM token이 다른 FID에 연결돼 있으면 이전 연결을
비활성화합니다.

#### Response 204

생성과 갱신 모두 본문 없이 `204 No Content`를 반환합니다.

#### Errors

- `COMMON_VALIDATION_FAILED`
- `COMMON_CONFLICT`
- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`

### DELETE /api/me/push-devices/{firebaseInstallationId}

현재 인증 사용자가 소유한 설치를 비활성화합니다. 로그아웃과 별도 endpoint이며, 앱은 로그아웃 직전에
호출합니다.

#### Response 204

자기 소유 설치만 비활성화하고, 이미 비활성화됐거나 없는 설치도 `204 No Content`로 멱등 처리합니다.
다른 사용자가 소유한 활성 설치는 해제하지 않습니다. 물리 삭제하지 않아 같은 사용자의 재등록과 token
lifecycle을 안전하게 처리합니다.

#### Errors

- `AUTH_UNAUTHORIZED`
- `AUTH_INVALID_TOKEN`

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
클릭 시 `signal_id`로 Signal 상세 화면을 엽니다.
