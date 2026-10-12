# Signal push 전달 계약

서버 `notification`의 현재 설치 lifecycle·소비·전달·복구 계약이다.
소유권은 [ADR-0009](../adr/0009-notification-consumer-ownership.md), 모듈 경계는
[모듈 맵](module-map.md), HTTP 필드는 [OpenAPI](../spec/openapi.md), 앱 연동은
[모바일 API](../spec/mobile-api.md)를 따른다. 데모 배경·준비·완료 이력은 MOM-0689·0690과
[PR #100](https://github.com/momens-works/momens-server/pull/100)에 있다.

## Dev Signal 생성과 원자성

`signal.dev`의 생성 도구는 `@DevOnly`로 제한한다. prod에는 endpoint가 등록되지 않는다.
일반 사용자 project 멤버십 대신 dev 관리자 인가를 사용한다. 입력·오류 계약은 controller/DTO와
OpenAPI에서 확인한다. 도구 제거는 worker 경로 준비 후 **MOM-0944**가 담당한다.

한 DB 트랜잭션에서 다음을 모두 저장한다.

1. evidence별 `source_refs`
2. `signals`
3. evidence별 `signal_evidence`
4. `signal.created` `outbox_events`

하나라도 실패하면 모두 rollback한다. HTTP 성공은 위 데이터가 commit됐다는 뜻이며 FCM 발송 성공까지 기다리지
않는다.

## 이벤트와 push message

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



### 표시 문구

제목과 본문은 admin 요청으로 재정의하지 않는다. 저장된 Project와 Signal을 기준으로 서버가 생성한다.

```text
제목: {프로젝트명}에 새 시그널이 발견되었습니다.
본문: {시그널 제목}
```

서버는 프로젝트명과 Signal 제목을 임의로 자르지 않는다. Android 표시 영역에서의 말줄임은 앱/OS 책임이다.

### FCM data payload

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

## 설치 lifecycle

HTTP 등록·해제는 mobile이 `PushDeviceRegistrar`에 위임한다. FID는 설치본의 canonical identity,
FCM token은 현재 전송 주소다. 한 사용자는 여러 FID를 가지며 한 FID는 한 시점에 한 활성 사용자에게
귀속된다. platform은 android다.

- 같은 FID 재등록은 token 갱신·활성화다. 다른 사용자 소유면 원자적으로 이전한다.
- 활성 token이 다른 FID에 있으면 이전 연결을 비활성화한다. 부분 unique 제약과 같은 트랜잭션으로
  중복 활성 token을 막는다.
- 없는 FID/token에도 경합하므로 등록·해제는 PostgreSQL transaction advisory lock으로 직렬화한다.
  잔여 제약 위반은 token을 포함할 수 있는 DB 원인 예외를 로그로 넘기지 않고 `409 COMMON_CONFLICT`로 낸다.
- 해제는 로그아웃과 별개이며 앱이 로그아웃 직전에 호출한다. 자신의 설치만 비활성화하고 물리 삭제하지
  않는다. 이미 없거나 비활성인 설치는 멱등 성공하며 다른 사용자의 활성 설치는 해제하지 않는다.
- token과 Firebase 인증 정보는 로그에 남기지 않는다.

## 수신자 결정

1. outbox event의 `aggregate_id`로 Signal을 조회한다.
2. Signal의 `project_id`로 Project를 조회한다.
3. 소비 시점의 `workspace_id`에 속한 모든 구성원의 사용자 식별자를 `WorkspaceMembershipReader.listMemberUserIds`를 통해 조회한다.
4. 구성원들의 `active=true`, `platform=android` 설치를 모두 조회한다.
5. 한 사용자의 여러 활성 설치에 모두 발송한다.

owner, admin, member 역할을 구분하지 않는다. Signal 생성자도 workspace 구성원이면 수신한다. 활성 설치가 없는
구성원은 정상적으로 건너뛰며 event 실패로 처리하지 않는다.

수신자는 Signal 생성 시점이 아니라 `signal.created` 소비 시점에 확정한다.

## 소비와 전달

### 소비 순서

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
끝만 사용한다. 최초 기동 이전의 과거 이벤트를 일괄 push하는 backfill은 하지 않는다.

생산자 트랜잭션이 안전 지연보다 오래 열려 있으면 아직 commit되지 않은 낮은 id를 consumer가 관찰할 수
없으므로 보장 범위 밖이다. 생산자 트랜잭션이 안전 지연 안에 종료된다는 전제에서는 낮은 id의 event가 최대
안전 지연만큼 처리가 늦어질 수는 있어도 높은 id가 watermark를 건너뛰게 만들 수 없다. worker 전환 후에도
같은 규칙을 적용한다.

### Delivery 소유권

offset과 delivery 스키마는 [notification 마이그레이션](../../modules/notification/src/main/resources/db/migration/)에서 확인한다.
`(outbox_event_id, installation_id)` 복합 키로 소비 replay를 dedup한다.
`next_attempt_at`은 claim 중에는 lease 만료, 실패 기록 후에는 다음 재시도 시각을 뜻한다.
결과 기록·취소·lease 갱신은 현재 `claim_token` 소유자만 수행한다.

delivery는 token을 복제하지 않고 installation을 참조한다. 매 전송 시도 직전에 현재 installation이 활성이고
`installation.user_id == target_user_id`인지 다시 확인한다. FID가 다른 사용자에게 이전됐다면 이전 사용자를
위한 pending delivery는 `cancelled` 처리해 계정 간 오발송을 막는다. 같은 사용자의 token refresh는 현재 token으로
재시도할 수 있다.

### 발송과 재시도

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
  전에 종료되면 처리 lease 만료 후 재시도되며, 이는 아래 at-least-once 특성 안에 있다.
- 정상적인 최초 발송 목표는 Signal 생성 API 성공 후 2~4초다(1초 폴링 + 2초 안전 지연).
- 최초 1회 전송 후 일시 실패는 `1초 → 5초 → 30초` 간격으로 최대 3회 재시도한다.
- 총 시도 횟수는 최초 전송을 포함해 최대 4회다.
- 한 기기의 실패가 다른 기기의 발송을 막지 않는다.
- 무효·만료 token은 재시도하지 않는다. installation은 클레임한 token이 현재 token과 일치할 때만
  비활성화해, token refresh 후 도착한 이전 token의 실패가 새 token을 닫지 않게 한다.
- 최종 실패는 delivery에 `failed`와 오류 분류를 남기고 별도 DLQ는 만들지 않는다.
- FCM multicast 요청은 Admin SDK 한도에 맞춰 최대 500개 token 단위로 분할한다.
- token, Firebase private key, notification 본문 원문을 오류 로그에 남기지 않는다.

### 전달 보장

전달 보장은 at-least-once다. unique delivery key와 `sent` 상태로 일반적인 중복을 막지만, FCM 성공 직후 서버가
상태 기록 전에 종료되면 같은 기기에 중복 notification이 도착할 수 있다. FCM 전송에는 서버가 지정하는
idempotency key 기반 exactly-once 기능이 없으므로 이 짧은 중복 가능성을 수용한다.

## 쓰기 경계와 worker 전환

notification은 설치·delivery·consumer offset과 FCM 발송을 소유한다. outbox·signal·project·workspace
public API로 읽으며 SDK 타입은 FCM adapter 밖으로 노출하지 않는다.

Dev Signal writer는 `@Immutable` 읽기 엔티티를 재사용하지 않는 전용 insert 경로다.
source-ref는 source의 `DevSourceRefWriter`, outbox는 `OutboxAppender`를 호출자 트랜잭션에 합류시킨다.
운영 source-ref 쓰기 API와 이 dev 도구의 예외를 혼동하지 않는다.

worker는 Signal·source-ref·evidence·`signal.created`를 같은 backing 계약으로 생성한다.
producer만 바뀌며 consumer는 `issued_by`로 분기하지 않는다. SourceType의 `file` 등 worker 입력
정합성은 해당 작업에서 확인한다. 이 문서 정리는 proto 변경이나 worker 전환 완료를 뜻하지 않는다.

FCM은 ADC와 명시적 대상 Firebase project를 사용한다. dev의 Workload Identity·로컬 ADC 설정은
[로컬 개발](../local-development.md), prod 활성화 확인은 [운영 준비 대장](../prod-readiness-ledger.md)을 따른다.
실제 token·credential·dev 공유 시크릿은 저장소에 기록하지 않는다.

## 적용된 migration의 과거 참조

Flyway checksum을 보존하기 위해 적용된 SQL의 주석은 바꾸지 않는다. SQL 주석의 이전 절 번호는
[변경 전 Signal push 설계](https://github.com/momens-works/momens-server/blob/93a8f733ffdfa5220348be321455ece5dd345d9e/docs/design/signal-push-demo-design.md)를 가리킨다.
현재 계약은 이 문서 본문을 따른다.
