# 0024. MCP grant 관리의 웹 HTTP 표면 분리

- 상태: Accepted
- 날짜: 2026-09-28
- 작성자: Kimgyuilli

## 맥락

MOM-0999의 H035·H036 이관에서 ADR-0023의 grant 관리 소유 표현과 모듈 맵의 웹 표면
소유 원칙을 함께 적용해야 한다. ADR-0023은 grant 관리를 `:mcp`에 두고, 모듈 맵은
웹 클라이언트가 호출하는 HTTP 표면을 `:web`에 두도록 정했다.

## 결정

사용자가 MOM-0999 착수 시 다음 배치를 확정했다. ADR-0023의 grant 관리 HTTP 표면
배치 부분은 이 결정으로 대체한다.

- H035·H036의 controller, 웹 DTO와 요청자 권한 검사는 `:web`이 소유한다.
- `McpGrant`, 사용 시각 기록, 조회·폐기와 연결된 token family 무효화는 `:mcp`가 소유한다.
- `:web`은 `McpGrantReader`, `McpGrantWriter`, `McpClientReader` 공개 계약을 사용한다.
  SAS 모델과 repository는 `:web`에 노출하지 않는다.
- 목록과 폐기는 현재 워크스페이스의 현재 사용자 연결로 제한한다. 관리자도 다른 사용자의
  연결을 관리하지 않는다.

OAuth protocol, consent interaction, MCP transport·도구의 배치는 이번 결정 범위 밖이다.
ADR-0023의 토큰 분리·재연결·canonical 주소 결정도 유지한다.

## 대안

HTTP controller까지 `:mcp`에 두면 현재 consent controller와는 같지만, 웹 표면을 `:web`에
모으는 프로젝트 원칙과 다르다. grant와 토큰 처리를 `:web`으로 옮기면 표면 모듈이
권한 lifecycle과 persistence를 소유하게 된다.

## 결과

웹 API는 사용자 세션으로 인증하고 `:mcp` 공개 API로 연결을 관리한다. `:web → :mcp`
의존을 추가하고 Modulith 경계 검증에 포함한다. 상세 wire 계약은
[grant 관리 API 명세](../spec/mcp-grants.md)에서 관리한다.
