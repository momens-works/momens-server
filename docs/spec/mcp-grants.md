# MCP 연결 조회·폐기 웹 API

본인 MCP 연결의 조회·폐기와 사용 시각 계약이다. HTTP 필드와 응답 예시는
[OpenAPI 스냅샷](openapi.json)의 `listWorkspaceMcpGrants`·`revokeWorkspaceMcpGrant`를 따른다.
책임 경계는 [ADR-0024](../adr/0024-mcp-grant-web-surface.md), 이관·스키마·동시 폐기 검증 이력은
MOM-0999와 [PR #286](https://github.com/momens-works/momens-server/pull/286)에 있다.

## 인증과 범위

- Momens 사용자 access token(Bearer 또는 기존 웹 쿠키)으로 인증한다. MCP reference token은
  이 API 인증에 사용할 수 없다. `API-Version`은 `1`이다.
- 두 API 모두 현재 workspace membership이 필요하다. 자기 자신이 승인한 연결만 조회·폐기한다.
  owner/admin도 다른 사용자의 연결을 폐기할 수 없다.
- 기존 grant/client/token은 이전하지 않는다. 신규 연결만 조회한다. FE base 전환과 운영
  canonical cutover는 MOM-0967·MOM-0992에서 수행한다.

## 목록

`GET /api/workspaces/{workspaceId}/mcp-grants`

성공은 `200`과 `{ "grants": [...] }`다. 활성 연결을 생성 시각 내림차순, 동일 시각에는
ID 오름차순으로 반환한다.

- `last_used_at`은 성공한 MCP token 검증 시각(UTC)이고 사용 전에는 생략한다.
- `revoked_at`은 활성 연결만 반환하므로 레거시와 같이 생략한다.
- 빈 목록은 `[]`로 반환한다.
- 등록 client가 없으면 레거시 JOIN과 같이 목록에서 제외한다. 저장소 조회 장애는 정상 빈
  목록으로 바꾸지 않는다.

## 폐기

`DELETE /api/workspaces/{workspaceId}/mcp-grants/{grantId}`

성공은 레거시와 같은 `204`, 본문 없음이다. grant와 연결된 모든 token family의
access/refresh token을 같은 트랜잭션에서 무효화한다. 토큰 저장이 실패하면 grant 폐기도
rollback한다. 커밋 후 해당 token의 MCP 접근·refresh를 거부하며 사용자 로그인 세션과
다른 연결은 유지한다.

없는 연결, 이미 폐기한 연결, 다른 사용자·workspace의 연결은 모두 `403`이다. 삭제 재시도를
성공으로 바꾸지 않는다. 동시 DELETE도 `revoked_at IS NULL` 조건의 원자적 UPDATE로 한 요청만
성공한다. 패배한 요청은 `403`이며 최초 폐기 시각을 덮어쓰지 않는다. 레거시에서 빠져 있던
폐기 시점의 membership 검사를 추가한다.

## 오류

입력·인가 오류는 Legacy compatible, 미인증·무효 토큰과 내부 장애는 Standard 형식이다.
상태 코드와 본문은 OpenAPI의 해당 operation을 따른다. 저장소 장애를 입력 오류나 빈 목록으로
바꾸지 않는다.

## 사용 시각과 스키마

reference token·resource·grant·scope·membership 검증을 모두 통과한 뒤 사용 시각을 기록한다.
목록 조회, 실패한 인증, OAuth 갱신만으로는 기록하지 않는다. 원자적 컬럼 UPDATE로 시간이
뒤로 가지 않게 하고, 폐기된 행에는 기록하지 않는다. grant 폐기는 폐기·감사 시각만 조건부
UPDATE해 동시 사용 시각 갱신을 덮어쓰지 않는다. 명시적 폐기와 재동의 교체는 모두 grant를
먼저 변경한 뒤 token family를 잠근다. 각 성공 인증에 DB 쓰기 한 번이 추가된다.
