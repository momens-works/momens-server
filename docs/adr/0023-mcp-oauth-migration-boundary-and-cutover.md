# 0023. MCP OAuth 인가 서버 모듈 경계와 재연결 전환

- 상태: Proposed
- 날짜: 2026-09-23
- 작성자: Kimgyuilli

## 맥락

레거시 `momens-api`의 MCP 표면은 MCP transport와 OAuth 인가 서버를 함께 제공한다. OAuth
자산은 `oauth_clients`, `oauth_interactions`, `oauth_grants`, `oauth_authorization_codes`,
`oauth_access_tokens`, `oauth_refresh_tokens` 여섯 테이블과 `internal/mcpauth` 구현으로
구성되어 있다.

신규 서버의 `:auth`는 Google OAuth client, Momens 사용자 access/refresh token, resource
server 기반 토큰 검증을 담당한다. MCP OAuth는 외부 MCP client에 사용자의 워크스페이스
접근을 위임하고 토큰을 발급하는 authorization server 역할이다. 두 흐름은 사용자 신원을
공유하지만 client, grant, scope, 토큰 수명주기와 revoke 단위가 다르다.

이번 결정은 다음 이관 표면을 대상으로 한다.

- H002~H008: OAuth metadata·client registration·authorize·token·revoke
- H009~H011: consent interaction 조회·승인·거절
- H012: MCP transport
- H035~H036: MCP grant 조회·폐기
- N009~N019: MCP 도구 11개

웹 컷오버 시점의 레거시 세션 수용은 이미 [ADR-0018](0018-transitional-legacy-acceptance-of-new-access-token.md)에서
별도로 결정했다. 여기서는 MCP/OAuth 표면 전체의 target 모듈, 토큰 이전, 주소 전환과 후속
구현 범위를 결정한다.

## 결정

### 1. MCP/OAuth는 별도 `:mcp` 모듈이 소유한다

MCP transport, OAuth authorization server, consent interaction, grant 관리와 MCP 도구를
`:mcp`에 둔다. `:mcp`는 사용자·워크스페이스·도메인 쓰기 기능이 필요할 때 `:auth`, `:user`,
`:workspace`, `:project`의 public API만 사용한다. 다른 모듈의 `internal` package에는
의존하지 않는다.

`:auth`는 Momens 사용자 로그인·세션·현재 사용자 인증을 계속 소유한다. MCP OAuth의
`client`, `grant`, authorization code, access token, refresh token과 관련 영속성은 `:mcp`가
소유한다.

### 2. 웹·모바일 사용자 토큰과 MCP client 토큰을 분리한다

사용자 세션 토큰과 MCP 토큰은 토큰 종류, audience/scope, 검증 정책, 만료·갱신·폐기
수명주기를 분리한다. 사용자 ID와 워크스페이스 membership 확인은 기존 public API를
통해 공유할 수 있지만, MCP 연결 하나를 revoke해도 Momens 웹·모바일 세션 전체가
폐기되지는 않는다.

인가 서버 구현은 Spring Authorization Server를 우선 사용한다. 표준 authorization code,
PKCE, client registration, token, revoke와 metadata 흐름을 기반으로 하고, MCP consent와
워크스페이스 grant는 `:mcp`의 저장소·서비스로 연결한다. Spring Boot/Spring Security
버전과 필요한 MCP 흐름의 호환성은 첫 구현 spike에서 검증한다. 호환성으로 인해 표준
구현을 사용할 수 없을 때만 별도 결정으로 대체한다.

### 3. 기존 grant/client/token은 이전하지 않고 전부 재연결한다

기존 `oauth_grants`, `oauth_clients`와 발급된 토큰은 신규 서버로 이전하지 않는다. 신규
MCP endpoint에 연결하는 모든 client는 신규 authorization endpoint에서 다시 동의하고,
신규 `:mcp` 토큰을 발급받아야 한다.

따라서 토큰 서명·암호화 형식의 레거시 호환은 요구하지 않는다. 전환 runbook에는 재연결
안내, 기존 grant/token의 만료·폐기 시점, 재연결 실패 시 확인 절차를 포함한다.

### 4. MCP canonical 주소는 신규 `/api` 경로로 전환한다

신규 MCP transport의 canonical resource URL은 다음으로 한다.

```text
https://api.momens.works/api/mcp
```

MCP resource metadata와 OAuth issuer 및 OAuth endpoint가 반환하는 URL도 신규 `/api`
주소 체계를 기준으로 구성한다. 표준 well-known endpoint의 정확한 외부 경로와 response
필드는 후속 protocol contract에서 고정한다.

기존 `https://api.momens.works/mcp` 주소를 장기 alias로 유지하지 않는다. 전환 후 기존
주소는 제거 또는 차단하고, MCP client는 신규 주소에서 재연결한다. 재연결을 전제로 하므로
기존 주소와 신규 주소의 grant/token을 병행 유지하지 않는다.

## 대안

**`:auth`에 MCP OAuth를 포함하는 방안은 채택하지 않았다.** `:auth`의 사용자 로그인·세션과
MCP authorization server는 역할과 보안 경계가 다르다. 한 모듈에 넣으면 사용자 세션과
외부 client grant의 토큰·revoke 수명주기가 섞이고 MCP 표면이 `auth` 내부 구현에
의존하기 쉽다.

**사용자 세션 토큰과 MCP 토큰을 같은 체계로 발급하는 방안은 채택하지 않았다.** MCP
client가 탈취한 토큰으로 웹·모바일 세션 권한을 행사할 수 있고, MCP 연결 하나를 revoke할
때 전체 사용자 세션을 폐기해야 하는 문제가 생긴다.

**기존 grant/client/token을 호환 이전하는 방안은 채택하지 않았다.** 레거시 토큰 형식과
서명·암호화, 저장 모델을 신규 서버가 계속 호환해야 하며, 이전 누락·중복·폐기 상태를
검증해야 한다. 이번 전환은 전면 재연결로 이 복잡성과 장기 호환 비용을 제거한다.

**기존 `/mcp`를 신규 서버의 alias로 장기 유지하는 방안은 채택하지 않았다.** canonical
주소가 둘로 남고 issuer/resource metadata와 client 설정의 출처가 불명확해진다. 재연결을
허용하므로 신규 `/api/mcp`로 단일화한다.

**OAuth protocol을 처음부터 커스텀 구현하는 방안은 기본안으로 채택하지 않았다.** 표준
authorization code, PKCE, redirect URI, token rotation과 revoke 검증 책임이 애플리케이션에
남는다. Spring Authorization Server 호환성 spike가 실패할 때만 대체안을 별도 결정한다.

## 결과

MCP/OAuth의 데이터와 외부 protocol 표면이 별도 모듈에 모여 기존 `auth`와 경계가
명확해진다. 사용자 세션과 MCP 위임 권한을 독립적으로 revoke·만료시킬 수 있고, 기존
레거시 토큰 형식에 대한 장기 호환 부담 없이 신규 주소로 전환할 수 있다.

대신 모든 MCP 사용자는 재연결해야 하며, 신규 authorization server와 consent UI가
준비되기 전까지 기존 MCP client는 신규 주소를 사용할 수 없다. 후속 구현은 OAuth
protocol core(H002~H008), interaction/grant UI API(H009~H011, H035~H036), MCP transport
(H012), MCP 도구(N009~N019)의 순서와 단일 writer·retrieval projection gate를 명시해야
한다. MCP 도구가 `tasks`를 쓰는 범위는 `MOM-0898`, `MOM-0956`, `MOM-0953`의 gate를
따른다.

이 결정으로 기존 이관 원장의 MCP/OAuth target module·grant/token 이전 미결정은 해소한다.
구현 중 protocol contract, token claim, scope 집합 또는 주소의 세부가 이 결정과 달라질
경우 이 ADR을 수정하지 않고 후속 ADR로 supersede한다.
