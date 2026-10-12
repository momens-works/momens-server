# Blocker 쓰기 API와 outbox 계약

MOM-1018은 H066·H074·H075를 이관한다. 레거시 기준은 `momens-api`
`66eed8da99775753f3e49ad0621cb60527d353c0`의 `internal/blocker`,
`internal/access/repository.go`, `internal/domain/models.go`,
`internal/retrieval/projection.go`, `migrations/000001_init.sql`이다.
갱신한 `origin/main`은 티켓 기준 `7739a77886f16b11ba1f8c3673a3f00f9828b096`이며,
확인한 checkout과 blocker·권한·모델·projection·해당 DDL의 차이가 없다.

## HTTP 계약

모든 요청은 인증과 `API-Version: 1`이 필요하다. 실패 응답은 티켓에서 지정한
Standard 형식이다. 성공 응답은 레거시 status와 필드를 유지한다.

| 요청 | 최소 권한 | 성공 |
| --- | --- | --- |
| `POST /api/tasks/{taskId}/blockers` | member | 201, blocker 객체 |
| `PATCH /api/blockers/{blockerId}/resolve` | member | 200, `{"message":"resolved"}` |
| `DELETE /api/blockers/{blockerId}` | admin (owner 포함) | 200, `{"message":"deleted"}` |

- 생성 body는 `description`과 선택적 `workspace_id`다. 설명 누락·null·빈 문자열은
  400이다. 공백만 있는 문자열은 레거시처럼 보존하며 trim하지 않는다.
- 삭제되지 않은 작업과 프로젝트를 확인하고 프로젝트에서 workspace를 구한다.
  요청의 workspace가 다르면 400 `BLOCKER_WORKSPACE_MISMATCH`다.
- `:web`이 대상 소속과 권한을 확인한 뒤 `BlockerWriter`에 확정한 workspace를 전달한다.
  writer의 해결·삭제도 workspace 범위를 제한한다. blocker는 다른 project 하위 도메인을
  참조하지 않으며, 생성의 작업 소속 검증은 호출자의 책임이다.
- 초기 상태는 `active`, 종류는 `task`이며 `task_id = blocked_entity_id`,
  `milestone_id = null`을 저장한다. 생성 응답의 `resolved_at`은 생략한다.
- 해결은 행 잠금을 얻은 뒤 하나의 시각으로 `resolved_at`과 `updated_at`을 갱신하고
  `status = resolved`를 저장한다. 반복 해결도 200이며 시각을 다시 갱신한다.
- 삭제는 물리 삭제다. 삭제된 blocker의 해결·삭제는 404 `BLOCKER_NOT_FOUND`다.
- 기존 milestone blocker도 해결·삭제할 수 있다. milestone 생성 HTTP API(H059)는 추가하지 않는다.
- snapshot은 기존 필드와 정렬을 유지하며 active·resolved를 모두 반환한다.
- 기존 blocker DDL의 컬럼·제약이 쓰기를 지원하므로 이번 이관에는 Flyway 변경이 없다.

## 생산자 이벤트

도메인 변경과 outbox INSERT는 같은 트랜잭션이다. 해결·삭제는 blocker 행 잠금으로
직렬화하며, outbox 실패 시 도메인 변경도 롤백한다.

공통 envelope는 `issued_by = api-server`, `aggregate_type = blocker`,
`aggregate_id = blocker UUID 문자열`, `workspace_id = blocker의 workspace`다.
모든 payload는 빈 객체 `{}`다. 문서 삭제 식별에 필요한 정보는 envelope에 이미 있다.

| event_type | idempotency_key | 발행 시점 |
| --- | --- | --- |
| `blocker.created` | `blocker.created:{blockerId}` | 생성 |
| `blocker.resolved` | `blocker.resolved:{blockerId}:{changeUUID}` | 반복 호출을 포함한 매 해결 |
| `blocker.deleted` | `blocker.deleted:{blockerId}` | 물리 삭제 |

같은 outbox INSERT 재시도는 같은 키를 사용한다. 해결 HTTP 요청 자체의 중복 제거는
보장하지 않는다. 재요청은 레거시처럼 새로운 해결 시각과 새 이벤트를 만든다.

## Worker 소비 계약 (MOM-1021)

이 절은 후속 worker 구현의 계약이며, 이번 서버 구현에 projector는 포함하지 않는다.

- 생성·해결 이벤트는 envelope의 workspace와 blocker ID로 **최신** 원본을 hydrate한다.
  `resolved`도 검색 문서를 유지하고 상태·해결 시각이 포함된 본문을 갱신한다.
- 원본이 없으면 검색 문서와 source-ref를 삭제 상태로 수렴시킨다. 삭제 이벤트는 원본
  조회 없이 envelope만으로 정리한다. 따라서 물리 삭제 뒤 과거 생성·해결 이벤트를
  재처리해도 검색 문서가 부활하지 않아야 한다.
- 레거시와 같은 UUID v5(SHA-1)를 사용한다. namespace는
  `8e3c1f76-5dc1-4f6e-9d54-2b9f6a1cb7a3`, document name은
  `blocker:{blockerId}`, source-ref name은 `source_ref:blocker:{blockerId}`다.
  UUID 문자열은 소문자 하이픈 형식이다. 삭제는 두 결정적 ID 모두에 적용한다.
- task blocker 문서 종류는 `TASK`, 기존 milestone blocker는 `MILESTONE_CONTEXT`다.
  레거시의 `MOMENS_INTERNAL`, `WORKSPACE`, memory type `RISK`, source object type
  `DOCUMENT`, blocker/blocked entity metadata와 본문 의미를 유지한다.
- 제목의 레거시 `description[:80]`은 UTF-8 바이트 중간을 자를 수 있으므로 그대로 이식하지 않는다.
  80바이트 제한을 유지하되 마지막 완전한 UTF-8 문자 경계까지만 취한다. `Blocker: ` 접두사와
  원문 description/text는 유지한다. 한글 27자와 ASCII 뒤 이모지가 이어지는 경계 입력으로
  유효한 UTF-8 제목 생성과 projection 저장을 검증한다(MOM-1021).
- 같은 ID의 처리와 최신 상태 조회·projection 쓰기는 worker에서 순서를 보장해야 한다.
  오래된 hydrate 결과를 나중에 덮어쓰지 않도록 해야 하며, 중복 소비·삭제 후 과거 이벤트
  재처리·경합 테스트는 MOM-1021의 완료 조건이다. 단순 event ID 순서만으로 commit 순서를
  가정하지 않는다. 재시도·offset·DLQ는 기존 worker 소비 기반이 소유한다.

서버 구현만으로 검색 반영과 운영 전환이 완료되지는 않는다. MOM-1021 projector,
MOM-1022 E2E, MOM-0967 트래픽 전환, MOM-1023 레거시 종료가 후속 범위다.
