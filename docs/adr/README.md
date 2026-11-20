# ADR (Architecture Decision Records)

결이 갈렸거나 맥락이 필요한 결정을 시점 기록으로 남깁니다. 새 ADR은
[`0000-template.md`](0000-template.md)를 복사해 작성합니다.

## 작성 범위

기존 ADR에서 합의한 책임·보장·핵심 제약의 의미가 달라지면 후속 ADR을 작성합니다.
알림 소비 주체나 원자적 저장 보장을 바꾸는 경우가 이에 해당합니다. 기존 결정을 유지하는
구현 변경은 PR에 근거를 기록하고 관련 규칙·명세·설계 문서를 갱신합니다. 클래스명·경로가
ADR에 등장한다는 이유만으로 후속 ADR을 요구하지 않습니다. 다만 구현 변경이라도 새로운
중요한 보안·운영 제약이나 책임 변경이 생기면 ADR 필요성을 검토합니다.

결정에는 팀이 유지할 책임과 보장을 명시하고, 구현에서 선택할 수 있는 범위와 배경·예시를
구분합니다. 타입명·설정 키·현재 구현 목록을 필요 이상으로 고정하지 않습니다. 토큰 원문 저장
금지나 원자적 저장처럼 중요한 보장은 구체적인 내용이어도 결정에 포함합니다. 합의한 원칙에
따른 개별 배치와 구현 선택은 해당 PR에 근거를 남깁니다.

## 상태와 적용 시점

| 상태 | 의미 |
| --- | --- |
| Proposed | 검토 중인 제안이며 현행 결정을 대체하지 않습니다. |
| Accepted | 팀이 채택한 설계입니다. 구현 완료를 뜻하지 않으며 구현 진행은 티켓·PR에서 추적합니다. |
| Superseded | 승인된 후속 ADR로 대체된 결정입니다. 일부만 대체되면 Accepted를 유지하고 대체 범위와 후속 ADR을 표시합니다. |

설계를 승인하는 PR의 머지를 적용 시점으로 삼습니다. 승인할 변경에는 Accepted 전환,
현행 규칙 반영, 기존 ADR의 대체 범위 표시와 인덱스 갱신을 함께 포함합니다. 실제 API·운영
명세에는 구현되지 않은 동작을 현재 동작으로 표시하지 않습니다.

제안을 보관하기 위해 머지할 때는 Proposed를 유지하고, 현행 규칙과 기존 ADR을 이미 교체된
것처럼 표시하지 않습니다. 구현 검증이 설계 승인의 전제라면 필요한 검증 조건을 명시하고,
그 조건이 충족되어 승인될 때까지 현행 규칙 교체를 유보합니다.

## 기존 기록 점검

ADR 본문 상태·인덱스·현행 규칙·후속 ADR의 대체 표시를 함께 대조합니다. Accepted ADR의
역사적 결정 본문은 보존하고, 변경된 결정은 후속 ADR로 연결합니다. 기존 PR·합의 기록으로
확인되는 상태와 대체 표시의 누락은 정정하고, 근거와 점검 결과는 작업 PR에 기록합니다.
구현이나 머지 사실만으로 승인을 추정하지 않습니다. 승인 범위가 불명확하면 미확인 항목과
확인이 필요한 내용을 티켓·PR에 남기고, 해당 ADR에서 그 추적 위치를 안내합니다.

## 목록

| # | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-modular-monolith-rules.md) | 모듈러 모놀리스 아키텍처 룰 | Accepted (결정 1의 모듈 경계 기준 중 비즈니스 capability 항목은 [0025](0025-module-boundary-criteria.md)로 개정) |
| [0002](0002-gradle-multi-module-boundaries.md) | Gradle 멀티모듈을 물리 모듈 경계로 사용 | Accepted |
| [0003](0003-auth-session-transport-model.md) | 인증 세션·전송 모델: 모바일/웹 하이브리드 | Accepted |
| [0004](0004-token-issuance-verification-stack.md) | 토큰 발급·검증 스택: Resource Server + JOSE | Accepted |
| [0005](0005-refresh-token-storage-model.md) | Refresh token 저장 모델: 서버 저장형 + PostgreSQL 원장 | Accepted |
| [0006](0006-api-path-and-versioning-policy.md) | API path·버저닝 정책: `/api` 단일화 + 전 엔드포인트 버저닝 | Accepted |
| [0007](0007-signal-backing-and-module-boundary.md) | Signal backing과 모듈 경계: 신규 `signals` + `signal` 모듈 | Accepted |
| [0008](0008-outbox-worker-projection-boundary.md) | Projection 경계: api-server outbox 발행 + worker 소비 | Accepted (notification 부분은 [0009](0009-notification-consumer-ownership.md)로, 이벤트 목록은 [0015](0015-minsu-async-task-draft-generation.md)로 개정) |
| [0009](0009-notification-consumer-ownership.md) | Notification 소유권: api-server가 `signal.created` outbox consumer | Accepted |
| [0010](0010-event-contract-conventions.md) | 이벤트 계약 규약: event_type 네이밍·하위호환성·버저닝 | Accepted |
| [0011](0011-signal-evidence-and-task-draft-contract.md) | Signal evidence와 task draft 생산·저장 계약 | Accepted (draft 생성 시점·저장·outbox 부분은 [0015](0015-minsu-async-task-draft-generation.md)로, prod 스키마 소유권은 [0019](0019-prod-schema-ownership-transfer.md)로 대체) |
| [0012](0012-brief-signal-digest-backing.md) | 브리프 시그널 요약 문단의 backing과 모듈 소유권 | Accepted (prod 스키마 소유권에 관한 결정은 [0019](0019-prod-schema-ownership-transfer.md)로 대체) |
| [0013](0013-project-progress-derivation.md) | 프로젝트 진행률을 태스크에서 계산 | Accepted |
| [0014](0014-minsu-task-draft-module-and-llm-boundary.md) | Minsu task draft 모듈과 LLM 경계 | Accepted (생성 시점·저장·모듈 의존 부분은 [0015](0015-minsu-async-task-draft-generation.md)로 대체) |
| [0015](0015-minsu-async-task-draft-generation.md) | Minsu task draft 비동기 생성과 `minsu` 영속성 소유 | Accepted |
| [0016](0016-user-identity-key-google-sub.md) | 사용자 신원 식별 키를 이메일에서 Google `sub`으로 전환 | Accepted (prod 스키마 소유권에 관한 결정은 [0019](0019-prod-schema-ownership-transfer.md)로 대체) |
| [0017](0017-transitional-legacy-session-token-acceptance.md) | 전환기 레거시 `session_token` 한시 수용 | Accepted ([0003](0003-auth-session-transport-model.md)의 한시적 예외) |
| [0018](0018-transitional-legacy-acceptance-of-new-access-token.md) | 전환기 레거시의 신규 access token 수용 | Accepted ([0003](0003-auth-session-transport-model.md)의 한시적 예외, [0017](0017-transitional-legacy-session-token-acceptance.md)의 반대 방향) |
| [0019](0019-prod-schema-ownership-transfer.md) | prod 스키마 주도권을 서버로 이전 | Accepted |
| [0020](0020-mobile-workspace-scope.md) | 모바일 워크스페이스 범위: bootstrap의 워크스페이스 표현과 선택 소유권 | Proposed |
| [0021](0021-operation-id-naming-convention.md) | 엔드포인트 operationId 명명 규칙 | Accepted |
| [0022](0022-column-value-set-ownership.md) | 컬럼 허용 값의 관리 기준과 도메인 enum의 역할 | Accepted |
| [0023](0023-mcp-oauth-migration-boundary-and-cutover.md) | MCP OAuth 인가 서버 모듈 경계와 재연결 전환 | Accepted (grant 관리 HTTP 표면은 [0024](0024-mcp-grant-web-surface.md)로 대체) |
| [0024](0024-mcp-grant-web-surface.md) | MCP grant 관리의 웹 HTTP 표면 분리 | Accepted |
| [0025](0025-module-boundary-criteria.md) | 모듈 경계를 정하는 기준 | Accepted |
