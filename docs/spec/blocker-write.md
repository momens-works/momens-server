# Blocker 쓰기 API와 outbox 계약

Blocker 쓰기와 worker 검색 반영의 계약이다. HTTP 입력·응답 형식은
[OpenAPI 스냅샷](openapi.json)의 `createTaskBlocker`·`resolveBlocker`·`deleteBlocker`를 따른다.

## HTTP 계약

- 공백만 있는 설명은 trim하지 않고 그대로 저장한다.
- milestone blocker도 해결·삭제할 수 있다.
- snapshot은 active·resolved를 모두 반환한다.

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
보장하지 않는다. 재요청은 새로운 해결 시각과 새 이벤트를 만든다.

## Worker 소비 계약

- 생성·해결 이벤트는 envelope의 workspace와 blocker ID로 **최신** 원본을 hydrate한다.
  `resolved`도 검색 문서를 유지하고 상태·해결 시각이 포함된 본문을 갱신한다.
- 원본이 없으면 검색 문서와 source-ref를 삭제 상태로 수렴시킨다. 삭제 이벤트는 원본
  조회 없이 envelope만으로 정리한다. 따라서 물리 삭제 뒤 과거 생성·해결 이벤트를
  재처리해도 검색 문서가 부활하지 않아야 한다.
- UUID v5(SHA-1)를 사용한다. namespace는
  `8e3c1f76-5dc1-4f6e-9d54-2b9f6a1cb7a3`, document name은
  `blocker:{blockerId}`, source-ref name은 `source_ref:blocker:{blockerId}`다.
  UUID 문자열은 소문자 하이픈 형식이다. 삭제는 두 결정적 ID 모두에 적용한다.
- task blocker 문서 종류는 `TASK`, 기존 milestone blocker는 `MILESTONE_CONTEXT`다.
  레거시의 `MOMENS_INTERNAL`, `WORKSPACE`, memory type `RISK`, source object type
  `DOCUMENT`, blocker/blocked entity metadata와 본문 의미를 유지한다.
- 제목은 `Blocker: ` 접두사 뒤에 description을 80바이트 이내에서 마지막 완전한 UTF-8 문자
  경계까지 붙인다. 원문 description/text는 자르지 않는다.
- 같은 ID의 처리와 최신 상태 조회·projection 쓰기는 worker에서 순서를 보장해야 한다.
  오래된 hydrate 결과를 나중에 덮어쓰지 않도록 해야 하며, 중복 소비·삭제 후 과거 이벤트
  재처리·경합을 테스트로 확인한다. 단순 event ID 순서만으로 commit 순서를
  가정하지 않는다. 재시도·offset·DLQ는 기존 worker 소비 기반이 소유한다.
