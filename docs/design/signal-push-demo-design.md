# MOM-0689 Signal push 데모 설계

작성일: 2026-07-16

상태: 구현 전 확정 설계

## 1. 목적

dev 서버에서 admin 성격의 데모 API로 완전한 Signal을 생성하고, 생성된 Signal의 프로젝트가 속한
워크스페이스 전체 구성원의 Android 기기에 push notification을 발송한다.

이번 데모의 완료 흐름은 다음과 같다.

```text
dev access token 발급
  → Signal 생성 API 호출
  → signals + source_refs + signal_evidence + signal.created 원자 저장
  → api-server가 signal.created 소비
  → 소비 시점 workspace 전체 구성원의 활성 Android 설치 조회
  → FCM 발송
  → 알림 클릭 시 Signal 상세 화면 이동
```

Signal 생성 주체는 데모에서는 api-server이고, 실제 운영 흐름에서는 worker로 전환한다. 생산자가 바뀌어도
`signal.created` 이후의 notification consumer와 FCM 발송 흐름은 바꾸지 않는다.

## 2. 범위

### 포함

- dev 전용 Signal 생성 API
- Signal과 evidence 원본의 완전한 저장
- `signal.created` outbox 발행과 notification 소비
- 인증 사용자별 Android 설치/FID/FCM token 등록·해제
- 워크스페이스 전체 구성원의 모든 활성 Android 설치로 발송
- FCM 일시 실패 재시도, 무효 token 비활성화, 기기별 발송 상태 관리
- Firebase dev 프로젝트와 dev Kubernetes 인증 정보 준비

### 제외

- 실제 source ingestion 또는 worker의 Signal 생성 구현
- iOS push
- task 담당자 할당, task 변경 등 `signal_created` 이외 notification
- 앱 foreground 상태에서 notification banner를 직접 표시하는 Android 구현
- Android notification permission 요청과 channel UI 구현
- Signal type(`risk`, `decision`, `change`, `question`)을 FCM data payload로 전달하거나 push에 태그로 표시
- 공통 proto에 `SOURCE_TYPE_FILE`을 추가하는 작업
- prod 배포 및 prod Firebase 프로젝트 구성
- 별도 DLQ와 운영자 재처리 화면

## 3. 기존 결정과의 관계

- [ADR-0008](../adr/0008-outbox-worker-projection-boundary.md)의 ID 중심 outbox와 생산자 원자 발행 원칙을
  따른다.
- [ADR-0009](../adr/0009-notification-consumer-ownership.md)에 따라 api-server가 `signal.created`를 소비해
  notification을 발송한다.
- [ADR-0010](../adr/0010-event-contract-conventions.md)의 event type 네이밍과 additive-only 규칙을 따른다.
- [ADR-0011](../adr/0011-signal-evidence-and-task-draft-contract.md)의 evidence 생산·저장 계약을 따른다.
- 기존 설계는 worker가 Signal을 만드는 운영 흐름을 설명한다. 이번 문서의 api-server Signal 생성은 dev
  데모를 위한 한정된 생산자 대체다. 운영 책임 경계를 변경하지 않는다.

## 4. 인증과 접근 범위

### 4.1 dev access token 발급

Signal 생성 API에 `X-Dev-Token-Secret`을 직접 보내지 않는다. 기존 dev token API에서 Bearer access token을
먼저 발급받는다.

```http
POST /api/dev/auth/token
X-Dev-Token-Secret: {dev-secret}
API-Version: 1
```

발급 대상 이메일은 기존 `momens.auth.dev-token.allowed-emails` 설정을 따른다.

### 4.2 Signal 생성 권한

- 생성 API는 `@DevOnly`와 동일하게 `local`, `dev`, `test` 중 하나가 활성이고 `prod`가 아닐 때만 존재한다.
- 유효한 Bearer access token을 가진 dev 사용자는 모두 호출할 수 있다.
- 호출자의 workspace 멤버십이나 owner/admin role을 검사하지 않는다.
- `prod` 프로필에서는 endpoint 자체를 등록하지 않는다.

기기 등록·해제 API는 장기적으로 worker 전환 이후에도 필요하므로 dev 도구로 한정하지 않는 인증 사용자 API다.

## 5. Dev Signal 생성 API

### 5.1 Endpoint

```http
POST /api/dev/projects/{projectId}/signals
Authorization: Bearer {access-token}
API-Version: 1
Content-Type: application/json
```

`project_id`는 body에 중복해서 받지 않는다. 서버가 path의 project를 조회해 `workspace_id`를 결정한다.

### 5.2 Request

```json
{
  "type": "risk",
  "title": "결제 정책 결정 3일째 보류",
  "description": "결제 정책 결정이 3일 동안 보류된 상태입니다.",
  "impact": "Q2 Activation 일정 지연 가능성",
  "minsu_suggestion": "결제 정책 의사결정 미팅 제안",
  "occurred_at": "2026-07-16T10:30:00+09:00",
  "evidence": [
    {
      "source_type": "slack",
      "source_title": "결제 정책 논의",
      "source_snippet": "정책 결정이 아직 보류 상태입니다.",
      "source_text": "원문 전체 내용",
      "source_url": "https://example.com/source/1",
      "occurred_at": "2026-07-16T09:20:00+09:00",
      "details": {
        "target": "결제 정책",
        "change": "결정 3일째 보류",
        "impact": "일정 지연 가능"
      }
    }
  ]
}
```

### 5.3 입력 계약

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `type` | 필수 | `decision`, `risk`, `question`, `change` |
| `title` | 필수 | 공백 문자열 불가 |
| `description` | 필수 | 공백 문자열 불가 |
| `impact` | 선택 | Signal 전체 영향 |
| `minsu_suggestion` | 선택 | nullable |
| `occurred_at` | 선택 | ISO-8601 시각 |
| `evidence` | 선택 | 생략 또는 빈 배열 허용, 배열 순서가 `sort_order` |
| `evidence[].source_type` | evidence가 있으면 필수 | `slack`, `figma`, `github`, `file` |
| `evidence[].source_title` | 선택 | `source_refs.title`에 저장 |
| `evidence[].source_snippet` | 선택 | `source_refs.snippet`에 저장 |
| `evidence[].source_text` | 선택 | `source_refs.text`에 저장 |
| `evidence[].source_url` | 선택 | nullable |
| `evidence[].occurred_at` | 선택 | `source_refs.source_created_at`, ISO-8601 시각 |
| `evidence[].details.target` | 선택 | 공백 포함 30자 이하 |
| `evidence[].details.change` | 선택 | 공백 포함 30자 이하 |
| `evidence[].details.impact` | 선택 | 공백 포함 30자 이하 |

`signals.metadata`는 admin 요청으로 받지 않는다. 현재 상세 조회나 push에 사용되지 않는 값을 데모 API에서
추가로 생산하지 않는다.

`source_title`, `source_snippet`, `source_text`는 원천 backing을 완전하게 저장하기 위한 선택 필드다. 현재
Signal 상세 API의 evidence 카드는 이 필드를 반환하지 않고 `source`, `occurred_at`,
`details(target/change/impact)`, `source_url`을 반환한다.

`occurred_at`은 Signal·evidence 모두 계약상 선택이지만, 비어 있으면 상세 화면의 시각 표시가 비므로 데모
시연 요청에서는 두 값을 채워 보내는 것을 권장한다.

### 5.4 서버 생성 값

- Signal ID
- source ref ID
- `source_refs.source_object_type`(`UNKNOWN`)과 `source_object_id`(생성한 source ref ID)
- `workspace_id`
- evidence `sort_order`
- `created_at`, `updated_at`
- outbox `id`, `idempotency_key`, `created_at`

### 5.5 원자성

한 DB 트랜잭션에서 다음을 모두 저장한다.

1. evidence별 `source_refs`
2. `signals`
3. evidence별 `signal_evidence`
4. `signal.created` `outbox_events`

하나라도 실패하면 모두 rollback한다. HTTP 성공은 위 데이터가 commit됐다는 뜻이며 FCM 발송 성공까지 기다리지
않는다.

### 5.6 Response

```http
201 Created
```

```json
{
  "id": "6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1"
}
```

신규 API이므로 실패 응답은 [Standard API 오류 규격](../spec/api-response-error-codes.md)을 따른다.

- body 검증 실패: `400`
- Bearer token 없음·무효: `401`
- project 없음: `404 PROJECT_NOT_FOUND`
- 호출자의 project/workspace 멤버십 부족으로 인한 `403`은 반환하지 않는다.

## 6. `signal.created` 계약

기존 확정 계약의 payload를 변경하지 않는다.

```text
issued_by       = api-server       # dev admin 생성
workspace_id    = project.workspace_id
aggregate_type  = signal
aggregate_id    = signal_id
event_type      = signal.created
payload         = {}
idempotency_key = signal.created:{signal_id}
```

운영 worker 전환 후에는 `issued_by=worker`만 달라진다. consumer는 `issued_by`로 필터링하지 않고
`event_type=signal.created`를 소비한다.

Outbox payload와 FCM data payload는 서로 다른 계약이다.

```text
outbox envelope + aggregate_id
  → Signal·Project를 DB에서 hydrate
  → notification 문구와 FCM data payload 생성
```

## 7. Push message 계약

### 7.1 표시 문구

제목과 본문은 admin 요청으로 재정의하지 않는다. 저장된 Project와 Signal을 기준으로 서버가 생성한다.

```text
제목: {프로젝트명}에 새 시그널이 발견되었습니다.
본문: {시그널 제목}
```

예시:

```text
Q2 Activation Readiness에 새 시그널이 발견되었습니다.
결제 정책 결정 3일째 보류
```

서버는 프로젝트명과 Signal 제목을 임의로 자르지 않는다. Android 표시 영역에서의 말줄임은 앱/OS 책임이다.

### 7.2 FCM data payload

```json
{
  "notification_type": "signal_created",
  "destination": "signal_detail",
  "signal_id": "signal-uuid",
  "project_id": "project-uuid",
  "workspace_id": "workspace-uuid"
}
```

- `notification_type`은 알림이 발생한 이유다. 추후 `task_assigned`, `task_updated` 등을 추가할 수 있다.
- `destination`은 클릭 시 이동할 화면이다. 같은 화면으로 가는 서로 다른 알림을 type으로 구분할 수 있다.
- 내부 outbox event type인 `signal.created`를 모바일 계약으로 직접 노출하지 않는다.
- `signal_type`은 포함하지 않는다. 클릭 후 Signal 상세 API가 반환하는 값을 사용한다.
- 알림 클릭 시 `signal_id`로 Signal 상세 화면을 연다.

FCM 메시지는 background/종료 상태의 시스템 표시를 위한 notification payload와 위 data payload를 함께 보낸다.
foreground 표시 방식은 Android 앱 책임이며 이 서버 설계에서 별도 분기하지 않는다.

## 8. Android 설치 등록·해제

### 8.1 식별 모델

- Firebase Installation ID(FID)를 앱 설치본의 canonical identity로 사용한다.
- FCM registration token은 FID의 현재 전송 주소다.
- 한 사용자는 여러 FID를 가질 수 있다.
- 한 FID는 한 시점에 한 활성 사용자에게만 귀속된다.
- 이번 범위에서 platform은 `android`만 허용한다.

### 8.2 등록 또는 token 갱신

```http
PUT /api/me/push-devices/{firebaseInstallationId}
Authorization: Bearer {access-token}
API-Version: 1
Content-Type: application/json
```

```json
{
  "fcm_registration_token": "...",
  "platform": "android"
}
```

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `firebaseInstallationId` (path) | 필수 | 공백 문자열 불가 |
| `fcm_registration_token` | 필수 | 공백 문자열 불가 |
| `platform` | 필수 | `android`만 허용 |

검증 위반은 [Standard API 오류 규격](../spec/api-response-error-codes.md)의 `400`으로 거부한다. 성공은
생성과 갱신 모두 `204 No Content`다.

- 같은 FID의 재요청은 token을 갱신하고 활성화한다.
- 다른 사용자에게 귀속된 FID면 현재 인증 사용자에게 원자적으로 소유권을 이전한다.
- 동일한 활성 FCM token이 다른 FID에 연결돼 있으면 이전 연결을 비활성화한다.
- token 또는 Firebase 인증 정보를 application log에 남기지 않는다.

등록·해제는 존재하지 않는 FID/token에도 동시 요청이 들어올 수 있어 row lock만으로 직렬화할 수 없다.
PostgreSQL transaction advisory lock으로 설치 원장 등록·해제 흐름을 직렬화하고, 제약 위반이 남으면 token이
포함될 수 있는 DB 원인 예외를 로그로 넘기지 않고 `409 COMMON_CONFLICT`로 반환한다.

### 8.3 설치 해제

```http
DELETE /api/me/push-devices/{firebaseInstallationId}
Authorization: Bearer {access-token}
API-Version: 1
```

- 로그아웃과 별도 endpoint로 둔다. Android 앱은 로그아웃 직전에 호출한다.
- 현재 인증 사용자가 소유한 설치를 비활성화한다.
- 이미 비활성화됐거나 없는 설치도 `204 No Content`로 멱등 처리한다.
- 다른 사용자가 소유한 활성 설치를 해제하지 않는다.
- 물리 삭제하지 않아 같은 사용자의 재등록과 token lifecycle을 안전하게 처리한다.

### 8.4 최소 저장 모델

`push_installations`는 최소 다음 상태를 가진다.

| 필드 | 용도 |
| --- | --- |
| `id` | 서버 내부 설치 ID |
| `firebase_installation_id` | FID, unique |
| `user_id` | 현재 소유 사용자 |
| `fcm_registration_token` | 현재 FCM 전송 token |
| `platform` | 이번 범위에서는 `android` |
| `active` | 발송 대상 여부 |
| `deactivated_at` | 로그아웃·무효 token 비활성화 시각 |
| `created_at`, `updated_at` | 감사 시각 |

활성 FCM token은 중복을 허용하지 않으며 활성 행에 대한 부분 unique 제약으로 강제한다. 소유권 이전과 token
이동은 이전 연결 비활성화와 같은 트랜잭션에서 처리해 제약 위반 없이 반영한다. 무효·만료 token에 대한 FCM
영구 실패는 설치 비활성화로 반영한다.

## 9. 수신자 결정

1. outbox event의 `aggregate_id`로 Signal을 조회한다.
2. Signal의 `project_id`로 Project를 조회한다.
3. 소비 시점의 `workspace_id`에 속한 모든 구성원의 사용자 식별자를 `WorkspaceMembershipReader.listMemberUserIds`를 통해 조회한다.
4. 구성원들의 `active=true`, `platform=android` 설치를 모두 조회한다.
5. 한 사용자의 여러 활성 설치에 모두 발송한다.

owner, admin, member 역할을 구분하지 않는다. Signal 생성자도 workspace 구성원이면 수신한다. 활성 설치가 없는
구성원은 정상적으로 건너뛰며 event 실패로 처리하지 않는다.

수신자는 Signal 생성 시점이 아니라 `signal.created` 소비 시점에 확정한다.

## 10. Notification consumer와 delivery 상태

### 10.1 처리 흐름

```text
1초 주기 outbox polling
  → watermark 이후 event를 id 순서로 조회
  → 안전 지연(2초)을 지나지 않은 첫 event에서 스캔 중단(prefix-cap)
  → signal.created 필터
  → Signal·Project hydrate
  → 수신 사용자와 활성 설치 결정
  → 기기별 pending delivery 원자 생성(next_attempt_at = 현재 시각)
  → watermark 전진
  → commit 후 발송 패스 즉시 트리거
```

api-server가 여러 인스턴스로 실행돼도 같은 outbox 구간을 동시에 materialize하지 않도록 consumer state를
잠근다. outbox row에는 처리 상태를 쓰지 않고 api-server가 자기 consumer 상태를 관리한다.

outbox `id`는 BIGSERIAL이라 id 발급 순서와 commit 순서가 다를 수 있다. 나중 id의 event가 먼저 commit되면
watermark가 아직 commit되지 않은 앞 id를 지나쳐 그 event를 영구히 건너뛴다. 또한 PostgreSQL `NOW()`는
트랜잭션 시작 시각이라 낮은 id의 짧은 트랜잭션보다 높은 id의 긴 트랜잭션이 더 오래된 `created_at`을 가질 수
있다. 따라서 중간 event를 `created_at`으로 걸러내지 않고, watermark 이후를 id 순서로 읽다가 DB 시계
기준 안전 지연(2초)을 지나지 않은 첫 event에서 멈춘다(prefix-cap). 최초 watermark 시드도 같은 prefix의
끝만 사용한다.

생산자 트랜잭션이 안전 지연보다 오래 열려 있으면 아직 commit되지 않은 낮은 id를 consumer가 관찰할 수
없으므로 보장 범위 밖이다. 생산자 트랜잭션이 안전 지연 안에 종료된다는 전제에서는 낮은 id의 event가 최대
안전 지연만큼 처리가 늦어질 수는 있어도 높은 id가 watermark를 건너뛰게 만들 수 없다. worker 전환 후에도
같은 규칙을 적용한다.

### 10.2 최소 상태 모델

`notification_consumer_offsets`:

- `consumer_name` primary key
- `last_outbox_id`
- `updated_at`

`push_deliveries`:

- `(outbox_event_id, installation_id)` 복합 primary key(별도 surrogate 없음)
- `target_user_id`
- `status`: `pending`, `sent`, `failed`, `cancelled`
- `attempt_count`
- `next_attempt_at`: materialization 시각으로 초기화. 클레임 중에는 처리 lease 만료 시각, 일시 실패
  결과 기록 후에는 다음 재시도 시각
- `claim_token`: 현재 전송 시도 소유권. 결과 기록·취소·lease 갱신은 token이 일치할 때만 반영
- `failure_category`
- `sent_at`, `created_at`, `updated_at`

delivery는 token을 복제하지 않고 installation을 참조한다. 매 전송 시도 직전에 현재 installation이 활성이고
`installation.user_id == target_user_id`인지 다시 확인한다. FID가 다른 사용자에게 이전됐다면 이전 사용자를
위한 pending delivery는 `cancelled` 처리해 계정 간 오발송을 막는다. 같은 사용자의 token refresh는 현재 token으로
재시도할 수 있다.

### 10.3 발송기와 재시도

발송은 materialization과 분리된 발송기가 delivery 행을 클레임해 수행한다. 최초 전송과 재시도가 같은
경로를 지난다.

- 발송기는 `status = pending`이고 `next_attempt_at`이 지난 delivery를 주기 스캔하고, `FOR UPDATE SKIP
  LOCKED`로 클레임해 여러 인스턴스가 같은 delivery를 이중 발송하지 않게 한다.
- 클레임 트랜잭션 안에서 `attempt_count`를 올리고 DB 시계 기준 처리 lease(30초) 만료 시각을
  `next_attempt_at`에, 새 `claim_token`을 소유권으로 먼저 기록한 뒤 commit한다. FCM 호출은 DB
  트랜잭션 밖에서 수행한다.
- 한 번에 클레임한 delivery를 event별로 발송하기 직전, 아직 claim token을 소유한 행의 lease를 갱신한다.
  배치 뒤쪽 event가 앞선 event 발송을 기다리다 lease를 잃으면 해당 인스턴스는 이를 발송하지 않는다.
- 결과 기록과 취소는 현재 `claim_token`이 클레임 값과 일치할 때만 반영한다. lease 만료 후 다른
  인스턴스가 재클레임했다면 이전 전송 결과가 최신 상태를 덮어쓰지 않는다.
- 일시 실패 결과를 기록하면 `next_attempt_at`을 결과 기록 DB 시각부터 계산한 재시도 백오프로 교체한다.
  처리 lease와 실패 후 재시도 간격을 같은 1초 값으로 겸용하지 않는다.
- materialization commit 직후 같은 인스턴스가 발송 패스를 즉시 실행해 지연을 줄인다. 이때도 클레임 경로를
  그대로 거친다.
- 서버가 최초 발송 전에 종료돼도 `pending` 행이 남아 있으므로 다음 스캔이 자연히 회수한다. 클레임 후 전송
  전에 종료되면 처리 lease 만료 후 재시도되며, 이는 10.4의 at-least-once 특성 안에 있다.
- 정상적인 최초 발송 목표는 Signal 생성 API 성공 후 2~4초다(1초 폴링 + 2초 안전 지연).
- 최초 1회 전송 후 일시 실패는 `1초 → 5초 → 30초` 간격으로 최대 3회 재시도한다.
- 총 시도 횟수는 최초 전송을 포함해 최대 4회다.
- 한 기기의 실패가 다른 기기의 발송을 막지 않는다.
- 무효·만료 token은 재시도하지 않는다. installation은 클레임한 token이 현재 token과 일치할 때만
  비활성화해, token refresh 후 도착한 이전 token의 실패가 새 token을 닫지 않게 한다.
- 최종 실패는 delivery에 `failed`와 오류 분류를 남기고 별도 DLQ는 만들지 않는다.
- FCM multicast 요청은 Admin SDK 한도에 맞춰 최대 500개 token 단위로 분할한다.
- token, Firebase private key, notification 본문 원문을 오류 로그에 남기지 않는다.

### 10.4 전달 보장

전달 보장은 at-least-once다. unique delivery key와 `sent` 상태로 일반적인 중복을 막지만, FCM 성공 직후 서버가
상태 기록 전에 종료되면 같은 기기에 중복 notification이 도착할 수 있다. FCM 전송에는 서버가 지정하는
idempotency key 기반 exactly-once 기능이 없으므로 이 짧은 중복 가능성을 수용한다.

## 11. 모듈 소유권과 쓰기 경로

[ADR-0009](../adr/0009-notification-consumer-ownership.md)가 구현 PR로 미뤄 둔 notification 소비 모듈
위치를 이 설계로 확정한다.

### 11.1 notification 모듈

`notification`을 신규 Gradle 모듈로 만든다. 다음을 소유한다.

- `push_installations`, `push_deliveries`, `notification_consumer_offsets` 테이블과 Flyway 마이그레이션
- `signal.created` consumer(watermark 관리 포함)와 FCM 발송기
- 설치 등록·해제 public API

기기 등록·해제 API(`/api/me/push-devices/{firebaseInstallationId}`)의 HTTP 표면은 mobile 모듈
presentation이 소유하고 notification의 public API에 위임한다. 앱이 호출하는 HTTP 엔드포인트는 mobile
모듈에 모은다는 표면 소유 원칙을 따르며, 같은 원칙에 따라 기존 `SignalController`를 signal → mobile
모듈로 옮기는 작업은 이 데모에 선행하는 별도 티켓으로 진행한다.

의존 방향은 다음과 같고, outbox 모듈이 다른 도메인 모듈을 참조하지 않는 기존 규칙은 유지한다.

- mobile → notification: 기기 등록·해제 HTTP 표면이 public API에 위임한다.
- notification → outbox: consumer 조회 public API를 outbox 모듈에 추가한다(watermark 이후 + 안전 지연
  필터). 기존 public API는 쓰기(`OutboxAppender`)뿐이다.
- notification → signal: `SignalReader`로 Signal을 hydrate한다.
- notification → project: Signal의 project 해석과 프로젝트명을 조회한다.
- notification → workspace: `WorkspaceMembershipReader.listMemberUserIds`로 수신자를 결정한다.

세 테이블은 레거시 미러가 아니라 이 서버가 소유하는 신규 상태다. prod 스키마 반영 경로는 prod 배포와 함께
범위 밖이며, 반영 시점에 별도로 확정한다.

### 11.2 dev Signal 생성 쓰기 경로

`signals`, `signal_evidence`, `source_refs`의 읽기 엔티티는 `@Immutable`이고, 운영 경로에서 api-server는
이 테이블을 쓰지 않는다(ADR-0007). dev 생성 API는 이 원칙의 dev 한정 예외이며 다음과 같이 격리한다.

- dev 생성 API controller와 `signals`·`signal_evidence` dev writer는 signal 모듈이 소유하고 `@DevOnly`로
  게이트한다. prod에는 빈 자체가 등록되지 않는다.
- `source_refs` 쓰기는 source 모듈에 `@DevOnly` 쓰기 public API를 추가해 위임한다. evidence hydrate에
  쓰는 기존 signal → source 의존 방향을 그대로 쓴다.
- dev writer는 `@Immutable` 읽기 엔티티를 재사용하지 않고 전용 insert 경로를 사용한다. 운영 경로의
  읽기 전용 원칙은 바뀌지 않는다.
- outbox 발행은 기존 `OutboxAppender`를 같은 트랜잭션에서 호출한다.

모듈 목록·경계 변경이므로 구현 PR에서 [모듈 맵](module-map.md)을 갱신한다.

## 12. Firebase와 배포 선행 작업

dev FCM 대상은 Android 팀이 생성한 Firebase 프로젝트 `momens-f4c02`이고, Android
applicationId는 `com.momens.android`이다. 아래 작업은 데모 구현·시연의 선행 조건이다.

1. dev 전용 Firebase 프로젝트 확인
2. Android 앱의 `applicationId` 확인
3. 해당 applicationId로 Firebase Android 앱 등록 확인
4. Android 앱에 같은 프로젝트의 `google-services.json` 적용
5. FCM 전송 권한만 가진 서버용 서비스 계정을 대상 Firebase 프로젝트 IAM에 등록
6. `momens-k8s-dev`의 전용 Kubernetes ServiceAccount와 GKE Workload Identity로 연결
7. api-server가 Firebase Admin SDK를 Application Default Credentials와 명시적인 대상 project ID로 초기화

FCM token과 `X-Dev-Token-Secret`은 서로 다른 인증 정보다. 실제 값은 저장소,
`application.yml`, `.env.example`에 넣지 않는다. dev GKE는 조직 정책상 서비스 계정 키 생성이 금지되어
있으므로 JSON 키나 credential Secret을 만들지 않고 Workload Identity가 ADC를 공급한다.
ADC 서비스 계정은 `momens-dev-mvp`에 있다. 대상 프로젝트에서
`momens-server-fcm@momens-dev-mvp.iam.gserviceaccount.com`에
`roles/firebasecloudmessaging.admin`을 부여한다.

참고: [Firebase Admin SDK 설정](https://firebase.google.com/docs/admin/setup),
[FCM Admin SDK 전송](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)

## 13. Worker 전환

데모와 실제 흐름의 차이는 Signal 생산자뿐이다.

| 구간 | 데모 | Worker 전환 후 |
| --- | --- | --- |
| Signal·evidence 생성 | dev admin API의 api-server | worker |
| `signal.created.issued_by` | `api-server` | `worker` |
| outbox aggregate/payload | `signal`, Signal ID, `{}` | 동일 |
| notification consumer | api-server | 동일 |
| 수신자·기기 조회 | api-server | 동일 |
| FCM 발송·재시도 | api-server | 동일 |

worker는 `signals`, `source_refs`, `signal_evidence`, `signal.created`를 같은 backing 계약으로 생성해야 한다.
consumer는 `issued_by`를 분기 조건으로 사용하지 않기 때문에 전환 시 notification 코드를 바꾸지 않는다.

공통 proto의 SourceType에는 현재 `file`이 없다. 이번 dev API와 DB는 문자열 계약으로
`slack`, `figma`, `github`, `file`만 허용하며, worker 전환에 필요한 proto 정합성은 별도 논의로 남긴다.

## 14. 기존 문서와의 차이

- [모바일 MVP 서버 요구사항](mobile-mvp-server-requirements.md)의 “push에서 Signal type을 참조해 category
  tag를 표시” 요구는 이번 계약으로 대체한다. FCM data에는 `signal_type`을 보내지 않는다.
- 기존 문서의 `signal.created.issued_by=worker`는 운영 생산자를 설명하며 유효하다. dev admin 생산 시에만
  일반 생산자 규칙에 따라 `api-server`를 사용한다.
- 모바일 Signal 계약의 source 집합은 더 넓을 수 있지만, dev 생성 API는 이번 데모에 합의한
  `slack`, `figma`, `github`, `file` 네 값만 허용한다.
- `signals.metadata`는 backing에 존재하지만 dev 생성 요청에서는 받지 않는다.

## 15. 완료 조건

- dev Firebase 프로젝트와 Kubernetes 서버 인증 정보가 준비된다.
- Android 앱이 FID와 FCM token을 등록할 수 있다.
- 같은 FID의 token refresh, 계정 전환, 로그아웃 해제가 오발송 없이 동작한다.
- dev Bearer 사용자가 workspace 멤버십과 무관하게 Signal 생성 API를 호출할 수 있다.
- evidence가 없는 Signal과 여러 evidence를 가진 Signal을 모두 생성할 수 있다.
- 허용하지 않은 Signal type 또는 source type은 `400`으로 거부된다.
- 기기 등록 API의 필수 필드·platform 검증 위반이 `400`으로 거부된다.
- 생성된 Signal은 기존 목록·상세 API에서 조회되고 evidence 카드가 기존 계약대로 표시된다.
- Signal 생성과 `signal.created`가 한 트랜잭션으로 저장된다.
- API는 commit 후 `201`과 Signal ID를 반환하고 FCM을 기다리지 않는다.
- 소비 시점 workspace 전체 구성원의 모든 활성 Android 설치가 발송 대상이 된다.
- push 제목·본문과 data payload가 이 문서의 계약과 일치한다.
- 정상 환경에서 API 성공 후 2~4초 안에 Android notification이 도착한다.
- 클릭 시 해당 Signal 상세 화면으로 이동한다.
- 일시 실패 재시도, 무효 token 비활성화, 기기별 실패 격리가 검증된다.
- 동일 outbox event의 delivery materialization은 기기별로 중복되지 않는다.
- 발송기 재시작 후 미발송 `pending` delivery가 회수되어 발송된다.
- 여러 인스턴스가 같은 delivery를 이중 클레임하지 않는다.
- [모듈 맵](module-map.md)에 notification 모듈과 dev 쓰기 경로가 반영된다.
