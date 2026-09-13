/**
 * dev 전용 토큰 발급 표면.
 *
 * <p>{@code POST /api/dev/auth/token}은 클라이언트가 호출하는 표면이 아니라 테스트·데모 도구이므로, 표면 모듈 소유 원칙(MOM-0850)의 대상이
 * 아니고 auth가 계속 소유합니다. 모든 빈은 {@code @DevOnly}로 게이트되어 prod에는 존재하지 않으며, 공개 노출도 전용 보안 체인 ({@code
 * DevTokenConfig})이 이 경로만 매칭해 처리합니다.
 *
 * <p>{@code signal.dev}와 달리 Spring Modulith nested 모듈로 선언하지 않습니다. dev 토큰은 운영과 같은 발급 경로 ({@code
 * internal.jwt})를 재사용해야 하고(ADR-0004), nested 모듈로 선언하면 그 참조가 경계 위반이 되기 때문입니다.
 */
package works.momens.server.auth.dev;
