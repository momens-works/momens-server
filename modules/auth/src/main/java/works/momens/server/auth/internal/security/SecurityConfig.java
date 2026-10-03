package works.momens.server.auth.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfigurationSource;
import works.momens.server.auth.internal.config.AuthProperties;

/**
 * 전역 SecurityFilterChain. 코드 소유권은 auth 모듈에 둡니다(docs/design/module-map.md).
 *
 * <p>두 개의 체인으로 나눕니다. 공개 경로(인증 엔드포인트·health·swagger)는 자원서버 필터를 태우지 않는 별도 체인으로 처리합니다. 그렇지 않으면 클라이언트가
 * 만료된 access token을 {@code Authorization} 헤더에 그대로 붙여 보낼 때(예: 토큰 갱신 직전 refresh 호출) 자원서버 필터가
 * permitAll 평가 전에 401을 내버려 갱신 흐름이 막힙니다. 보호 API 체인은 우리 HS256 디코더로 access를 검증하고, 거부 본문은 Standard 에러
 * shape로 통일합니다. 웹 쿠키 인증(MOM-22)은 {@link BearerTokenResolver}가 access 쿠키도 읽도록 교체해 보호 체인 위에 얹습니다.
 * same-domain 배포라 CSRF는 SameSite 쿠키 속성으로 막습니다(ADR-0003).
 *
 * <p>웹 프론트는 API와 origin이 달라, 두 체인 모두 {@link CorsConfigurationSource}를 물려 CORS를 처리합니다(MOM-55). 쿠키를
 * 실은 cross-origin 요청을 허용하려면 credentials를 켜고 origin을 명시해야 합니다. {@code /api/auth/web/*}는 공개 체인이라 여기에도
 * CORS가 필요하고, preflight(OPTIONS)는 Spring Security의 CORS 필터가 인증 전에 처리합니다.
 */
@Configuration
class SecurityConfig {

  /**
   * 전환기 한시 fallback: 레거시 {@code momens-api}의 {@code session_token} 쿠키(ADR-0017).
   *
   * <p>설정으로 두지 않고 상수로 고정합니다(전환기 코드임을 드러내기 위함). <b>제거 조건</b>: 웹 로그인이 신규 서버({@code
   * /api/auth/google/*})로 전환되어 모든 웹 세션이 {@code access_token} 쿠키를 갖게 되면 이 상수와 {@link
   * #bearerTokenResolver} 내 fallback 분기를 제거합니다. 조건 충족은 {@link BearerTokenResolutionMetrics}의 {@code
   * legacy_session_cookie} 원시 값이 아니라 <b>모든 인스턴스를 sum한 뒤 정한 관찰 기간의 increase가 0인 것</b>으로 확인합니다.
   * counter는 단조 증가라 원시 값이 0인 것은 재시작 직후에도 성립해 근거가 되지 못합니다. 관찰 기간과 집계 쿼리는 수집 백엔드를 배선할 때
   * 정합니다(MOM-0834, MOM-0875). 제거는 레거시 {@code momens-api} 종료와 묶지 않습니다.
   */
  private static final String LEGACY_SESSION_COOKIE_NAME = "session_token";

  private static final String[] PUBLIC_PATHS = {
    "/actuator/health/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/api/auth/google/token",
    "/api/auth/google/login",
    "/api/auth/google/callback",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/api/auth/web/refresh",
    "/api/auth/web/logout",
    "/api/source-connections/oauth/callback",
    "/api/mobile/app-update"
  };

  /**
   * 공개 경로 전용 체인. 자원서버를 얹지 않으므로 stale Bearer 헤더가 와도 401 없이 컨트롤러까지 도달합니다. 인증 엔드포인트는 바디 자격증명(idToken/
   * refreshToken)으로 동작하고 거부는 컨트롤러의 도메인 에러로 처리합니다.
   */
  @Bean
  @Order(1)
  SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
    // CorsConfigurationSource 빈(corsConfigurationSource)을 이름으로 찾아 적용합니다.
    http.securityMatcher(PUBLIC_PATHS)
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      JwtDecoder accessTokenDecoder,
      BearerTokenResolver bearerTokenResolver,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 무상태 Bearer/쿠키 API. same-domain 배포라 CSRF는 SameSite 쿠키 속성으로 막습니다(ADR-0003).
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .bearerTokenResolver(bearerTokenResolver)
                    .jwt(jwt -> jwt.decoder(accessTokenDecoder)))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  /**
   * Bearer 토큰 추출. 모바일은 Authorization 헤더, 웹은 access HttpOnly 쿠키로 보냅니다. 헤더를 먼저 보고 없으면 access 쿠키에서
   * 읽습니다. 쿠키 이름은 설정({@code momens.auth.web.cookie.access-name})을 따릅니다.
   *
   * <p>전환기에는 레거시 {@code session_token} 쿠키도 마지막 fallback으로 수용합니다(ADR-0017). 조회 순서는 {@code
   * Authorization} 헤더 → {@code access_token} 쿠키 → {@code session_token} 쿠키입니다. 디코더와 서명 키는 바꾸지 않습니다.
   *
   * <p>어느 단계에서 해석됐는지는 {@link BearerTokenResolutionMetrics}가 셉니다. fallback 제거 조건을 판단할 신호가 그것뿐입니다.
   */
  @Bean
  BearerTokenResolver bearerTokenResolver(
      AuthProperties properties, BearerTokenResolutionMetrics metrics) {
    String accessCookieName = properties.web().cookie().accessName();
    DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    return request -> {
      String fromHeader = headerResolver.resolve(request);
      if (fromHeader != null) {
        metrics.record(BearerTokenResolutionMetrics.Mode.HEADER);
        return fromHeader;
      }
      String fromAccessCookie = cookieValue(request, accessCookieName);
      if (fromAccessCookie != null) {
        metrics.record(BearerTokenResolutionMetrics.Mode.ACCESS_COOKIE);
        return fromAccessCookie;
      }
      String fromLegacyCookie = cookieValue(request, LEGACY_SESSION_COOKIE_NAME);
      metrics.record(
          fromLegacyCookie != null
              ? BearerTokenResolutionMetrics.Mode.LEGACY_SESSION_COOKIE
              : BearerTokenResolutionMetrics.Mode.NONE);
      return fromLegacyCookie;
    };
  }

  private static String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies() == null) {
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
