# 모바일 API 명세

이 문서는 모바일 앱의 호출 흐름과 상태·재시도·표시 의미를 설명합니다.
요청·응답 필드, 필수 여부, 허용 값과 오류 예시는 [OpenAPI 스냅샷](openapi.json)의 해당 경로에서
확인합니다. Swagger UI와 스냅샷 사용 방법은 [OpenAPI 안내](openapi.md#스냅샷-고정)를 따릅니다.

## 공통

- 시각 값: ISO 8601 UTC의 `Z` 표기로 반환하며, 표시 시간대 변환은 클라이언트가 담당합니다.
- 응답과 에러 형식: [API 응답과 에러 코드](api-response-error-codes.md)를 따릅니다.
- 경로·버전 규칙: [API 버저닝](api-versioning.md)을 따릅니다.

## 값과 화면 표시

### Signal

Signal type에 따라 앱이 다음처럼 화면 라벨을 정합니다. 이 라벨은 처리 상태나 목록 필터가 아닙니다.

| type | 화면 라벨 |
| --- | --- |
| `risk`, `change` | `Needs action` |
| `decision` | `Needs review` |
| `question` | `Needs decision` |

화면설계서의 Signal 카테고리 표시는 `type`의 첫 글자만 대문자로 바꿔 씁니다.

### Material

Signal 근거의 `evidence.source`와 작업의 `materials[].source`는 같은 source_ref 출처라 값 집합이 같습니다.
화면에 표시하는 출처 라벨과 아이콘은 앱이 `source`에서 생성합니다. 연결된 소스 화면은 figma, slack, github,
file을 표시하고, 목록에 없는 값이 와도 화면이 깨지지 않도록 처리합니다.

## 앱 업데이트

### GET /api/mobile/app-update

앱을 실행할 때 호출해 업데이트가 필요한지 확인합니다.

- `update_required`가 `true`일 때 사용자에게 업데이트를 안내하는 방식은 앱에서 결정합니다.
- 지원하지 않는 플랫폼, 형식이 올바르지 않은 앱 버전, 필수 쿼리 파라미터 누락은 `COMMON_VALIDATION_FAILED`로 응답하고 `details.fields`에 해당 쿼리 파라미터 이름을 담습니다. `platform`은 `ANDROID`처럼 enum 상수 이름으로 전달해도 허용합니다.

## 모바일 진입

### GET /api/mobile/bootstrap

접근 가능한 프로젝트가 없을 때의 빈 화면 처리는 앱이 담당합니다.

앱은 `onboarding_completed`가 `false`이면 코치마크를 표시하고, 사용자가 코치마크를 완료하면
`PUT /api/mobile/me/onboarding-completion`을 호출하여 완료를 기록합니다. 이 요청은 여러 번 보내도 결과가 같으므로
실패하면 다시 보내면 됩니다.

## 계정

### GET /api/mobile/me

workspace와 project 조회에 의존하지 않으므로, 프로젝트 조회에 실패하더라도 계정 정보를 조회할 수 있습니다.

## 프로젝트

### GET /api/mobile/projects/{projectId}/members

project가 없거나 삭제된 경우 `PROJECT_NOT_FOUND`로 응답합니다. project는 존재하지만 요청 사용자가 해당 workspace의 멤버가 아닌 경우 `AUTH_FORBIDDEN`으로 응답합니다.

## 시그널

한 사용자가 처리한 시그널은 프로젝트의 모든 사용자 목록에서 빠집니다. 액션 요청에 `Idempotency-Key`는 필요
없고, 이미 다른 액션으로 처리된 시그널에 대한 액션 요청은 `409 SIGNAL_INVALID_STATE`입니다.

### GET /api/mobile/projects/{projectId}/signals

`impact`는 목록 카드의 보조 문구로도 사용합니다.

### GET /api/mobile/signals/{signalId}

근거 행 헤더는 도구 이름(`source`)으로 표시합니다. 상대 시간 라벨은 앱이 `occurred_at`으로 렌더하고, 근거
개수에 따른 펼침·접힘·빈 상태도 앱이 담당합니다.

### POST /api/mobile/signals/{signalId}/actions/convert-to-task

`generating` 동안 다른 필드만 편집하거나 `title`, `role`, `priority`를 원래 값으로 되돌린 편집은 감지하지
못하므로, 그 뒤 생성 결과가 반영될 수 있습니다.

서버는 `draft_status`가 반드시 `ready`에 도달하도록 보장합니다(`generating`에 갇히지 않음). 생성 실패와
사용자 선행 편집도 구분하지 않고 모두 `ready`입니다.

`generating`을 어떻게 표시할지와 재조회 간격은 앱이 정합니다. 결과를 확인하는 경로는 이 응답과
작업 상세 둘뿐이고, **보드·목록에는 `draft_status`가 없습니다.** 보드 카드의 title은 사용자가
상세에 들어가지 않으면 이전 값인 채로 있다가 다음 목록 조회에서 조용히 바뀝니다.

### POST /api/mobile/signals/{signalId}/actions/dismiss

화면의 버튼 라벨은 `삭제`지만 서버 action 이름은 `dismiss`입니다. 물리 삭제나 시그널이 잘못됐다는 판정이
아니라, 작업으로 전환하지 않겠다는 처리 기록입니다.

## 브리프

### GET /api/mobile/projects/{projectId}/brief

브리프는 오늘의 브리프라, 시그널 요약은 그날(당일) 생성된 시그널을 처리 여부와 무관하게 집계하고 소프트 삭제된
시그널은 제외합니다. 하루의 시작 시각은 한국 시간(Asia/Seoul) 오전 6시입니다. `created_at`이 당일 오전 6시
이상, 다음 날 오전 6시 미만인 시그널을 조회합니다. 오전 6시 이전에 조회하는 경우에는 전날 오전 6시 이상, 당일
오전 6시 미만의 범위를 적용합니다.
이 집계 기준은 시그널 탭(처리 대기)과 다릅니다. 시그널 탭은 프로젝트 단위로 누적된 미처리 시그널을 보여주고,
브리프는 당일 생성분을 봅니다.

`filters`는 당일 시그널에 있는 type만으로 만들고, 개수가 0인 type은 칩을 만들지 않습니다. type 칩의 `label`은
위 Signal 카테고리 표시와 같습니다.

`signal_summary.summary`는 시그널 요약 헤더 아래 문단입니다. 문단은 `items`와 같은 하루 범위(생성 시각)로
조회하므로 문단과 목록의 기준일이 항상 같습니다. 같은 범위에 문단이 여러 건이면 가장 최근 것을 반환합니다.
화면의 "시그널 요약 · N" 헤더 숫자는 `filters`에서 `key`가 `all`인 항목의 `count`로 렌더링합니다(필터 선택과
무관하게 유지).

앱은 `items` 첫 페이지 중 최신 3개만 노출하고 더보기에서 나머지를 펼칩니다. 3개 노출은 화면 정책이며 서버는 첫
페이지 전체를 반환합니다. 커서는 서버만 해석하므로 앱은 내용을 해석하지 않고 그대로 되돌려 보냅니다.

`project.progress`는 조회할 때마다 작업 상태로 다시 계산하므로 상태 변경이 다음 조회에 바로 반영됩니다. 소프트
삭제된 작업은 제외하고, 작업이 없으면 0이며, 100은 cancelled를 제외한 모든 작업이 done일 때만 반환합니다.

화면의 "현재 우선순위 · N" 헤더 숫자는 `priorities` 배열 길이로 렌더링합니다.

### GET /api/mobile/projects/{projectId}/brief/signal-summary

정렬과 항목 구성은 브리프 본체의 `signal_summary.items`와 같습니다.

더보기는 접힌 상태의 3개 프리뷰를 목록으로 확장하는 동작입니다. 화면설계서의 "드롭다운에서 페이지네이션"은 전체를
한 번에 펼치는 것이 아니라, 목록을 연 뒤 내부 스크롤로 다음 페이지를 이어 조회하는 무한 스크롤을 의미합니다.

브리프 본체가 첫 페이지를 이미 반환하므로 처음 더보기를 열 때 다시 요청하지 않습니다. 필터를 바꾸면 `cursor`
없이 해당 필터의 첫 페이지를 요청합니다. 이후 목록 끝까지 스크롤하면 직전 응답의 `next_cursor`를 `cursor`로
전달해 다음 페이지를 조회하고, 반환된 항목을 기존 목록 뒤에 추가합니다.

## 작업

작업을 바꾸는 요청이 `409 COMMON_CONCURRENT_MODIFICATION`으로 실패하면
[태스크·마일스톤 수정 충돌](api-response-error-codes.md#태스크마일스톤-수정-충돌)의 재조회 절차를 따릅니다.

### GET /api/mobile/projects/{projectId}/tasks

날짜 표시 형식과 요일 계산은 앱이 처리합니다. 로그인한 사용자가 담당자인 태스크만 보려면 bootstrap 응답의
`me.id`를 `assignee_ids`로 전달합니다. 특정 날짜가 마감일인 태스크만 보려면 `due_date_from`과 `due_date_to`에
같은 날짜를 전달합니다.

### POST /api/mobile/projects/{projectId}/tasks

담당자·설명·완료 기준은 생성 후 수정 API로 지정합니다.

### GET /api/mobile/tasks/{taskId}

`convert-to-task`를 거치지 않은 작업의 `draft_status`는 항상 `ready`입니다. 이 조회는 짧은 간격의 폴링에는
적합하지 않습니다.

설명·소스·질문·다음 행동의 JSON 키는 각각 `purpose`, `materials`, `open_questions`, `next_action`입니다.
`materials`는 작업에 직접 연결된 소스이며, 메모리까지 포함하는 웹의 연결된 컨텍스트와는 다릅니다.

`materials[].summary`는 원본 snippet이 없으면 본문을 대신 사용하고, `materials`는 연결이 생성된 시각의 내림차순입니다.
`open_questions`는 생산자가 준 순서이고, 순서 값이 겹치면 `id` 순입니다. 질문과 다음 행동은 공백을 포함해
각각 50자, 100자 이하로 생산되며 서버는 자르지 않습니다.

앱은 `can_delete`로 삭제 버튼 노출 여부를 정합니다.

### PATCH /api/mobile/tasks/{taskId}

`materials`, `open_questions`, `next_action`은 요청에 없고 수정해도 유지됩니다. 빈 `title`은 상세 화면에서
'새 작업'으로 표시합니다. 완료 상태만 바꿀 때는 완료 기준 토글 엔드포인트를 사용하고, 새로 추가한 완료 기준의 서버
id는 저장 후 상세 조회에서 확인합니다.

### DELETE /api/mobile/tasks/{taskId}

이미 삭제된 태스크를 다시 삭제하면 `TASK_NOT_FOUND`입니다.

## Push 알림

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
동일 사용자의 token 갱신은 최신 token으로 재시도합니다. 운영 한계와 복구는 [운영 준비 대장](../prod-readiness-ledger.md#signal-push-운영)을 따릅니다.
