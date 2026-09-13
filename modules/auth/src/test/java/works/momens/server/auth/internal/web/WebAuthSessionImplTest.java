package works.momens.server.auth.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.auth.WebAuthCookieUpdate;
import works.momens.server.auth.WebAuthRedirect;
import works.momens.server.auth.internal.application.WebAuthService;
import works.momens.server.auth.internal.config.AuthProperties;
import works.momens.server.auth.internal.jwt.TokenPair;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserErrorCode;

/**
 * 웹 인증 전송 정책(쿠키 속성·리다이렉트 대상·실패 코드 매핑) 검증.
 *
 * <p>표면 컨트롤러는 이 결과를 응답에 옮기기만 하므로, 쿠키와 failure-uri 계약은 여기서 지킵니다(MOM-0852).
 */
class WebAuthSessionImplTest {

  private static final String SUCCESS_URI = "http://localhost:3000/auth/success";
  private static final String FAILURE_URI = "http://localhost:3000/auth/failure";

  private final WebAuthService webAuthService = mock(WebAuthService.class);
  private final WebAuthSessionImpl session =
      new WebAuthSessionImpl(
          webAuthService, new WebAuthCookies(authProperties()), authProperties());

  @Test
  @DisplayName("로그인 시작은 consent URL과 state·PKCE 핸드셰이크 쿠키를 돌려준다")
  void startLoginReturnsConsentUrlWithHandshakeCookies() {
    when(webAuthService.startLogin())
        .thenReturn(
            new WebAuthService.LoginRedirect(
                "https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz",
                "state-xyz",
                "verifier-xyz"));

    WebAuthRedirect result = session.startLogin();

    assertThat(result.redirectUri())
        .isEqualTo("https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz");
    assertThat(result.setCookieHeaders())
        .anyMatch(c -> c.startsWith("oauth_state=state-xyz") && c.contains("SameSite=Lax"))
        .anyMatch(
            c -> c.startsWith("oauth_pkce_verifier=verifier-xyz") && c.contains("SameSite=Lax"))
        .allMatch(c -> c.contains("HttpOnly"));
  }

  @Test
  @DisplayName("로그인 시작이 실패하면 쿠키 없이 failure-uri의 server_error로 보낸다")
  void startLoginRedirectsToServerErrorWhenStartFails() {
    when(webAuthService.startLogin()).thenThrow(new IllegalStateException("boom"));

    WebAuthRedirect result = session.startLogin();

    assertThat(result.redirectUri()).isEqualTo(FAILURE_URI + "?error=server_error");
    assertThat(result.setCookieHeaders()).isEmpty();
  }

  @Test
  @DisplayName("콜백 성공은 세션 쿠키를 설정하고 핸드셰이크 쿠키를 정리한 뒤 success-uri로 보낸다")
  void completeLoginIssuesSessionCookiesAndClearsHandshakeCookies() {
    when(webAuthService.completeLogin("auth-code", "state-xyz", "state-xyz", "verifier-xyz"))
        .thenReturn(new TokenPair("access-jwt", "refresh-token", 900));

    WebAuthRedirect result = session.completeLogin(callbackRequest(), "auth-code", "state-xyz");

    assertThat(result.redirectUri()).isEqualTo(SUCCESS_URI);
    assertThat(result.setCookieHeaders())
        .anyMatch(c -> c.startsWith("access_token=access-jwt") && c.contains("Path=/;"))
        .anyMatch(c -> c.startsWith("refresh_token=refresh-token") && c.contains("Path=/api/auth"))
        .anyMatch(c -> c.startsWith("oauth_state=") && c.contains("Max-Age=0"))
        .anyMatch(c -> c.startsWith("oauth_pkce_verifier=") && c.contains("Max-Age=0"));
  }

  @Test
  @DisplayName("핸드셰이크 거부는 invalid_state로 매핑하고 핸드셰이크 쿠키만 정리한다")
  void completeLoginMapsHandshakeRejectionToInvalidState() {
    when(webAuthService.completeLogin(any(), any(), any(), any()))
        .thenThrow(new BusinessException(AuthErrorCode.AUTH_OAUTH_STATE_INVALID));

    WebAuthRedirect result = session.completeLogin(callbackRequest(), "auth-code", "wrong-state");

    assertThat(result.redirectUri()).isEqualTo(FAILURE_URI + "?error=invalid_state");
    assertThat(result.setCookieHeaders()).allMatch(c -> c.contains("Max-Age=0")).hasSize(2);
  }

  @Test
  @DisplayName("검증되지 않은 Google 이메일은 email_not_verified로 매핑한다")
  void completeLoginMapsEmailNotVerified() {
    when(webAuthService.completeLogin(any(), any(), any(), any()))
        .thenThrow(new BusinessException(AuthErrorCode.AUTH_GOOGLE_EMAIL_NOT_VERIFIED));

    assertThat(session.completeLogin(callbackRequest(), "auth-code", "state-xyz").redirectUri())
        .isEqualTo(FAILURE_URI + "?error=email_not_verified");
  }

  @Test
  @DisplayName("code 교환 실패는 google_error로 매핑한다")
  void completeLoginMapsExchangeFailureToGoogleError() {
    when(webAuthService.completeLogin(any(), any(), any(), any()))
        .thenThrow(new BusinessException(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED));

    assertThat(session.completeLogin(callbackRequest(), "auth-code", "state-xyz").redirectUri())
        .isEqualTo(FAILURE_URI + "?error=google_error");
  }

  @Test
  @DisplayName("다른 로그인 수단에 연결된 이메일은 email_conflict로 매핑한다")
  void completeLoginMapsLinkedEmailToEmailConflict() {
    when(webAuthService.completeLogin(any(), any(), any(), any()))
        .thenThrow(new BusinessException(UserErrorCode.USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY));

    assertThat(session.completeLogin(callbackRequest(), "auth-code", "state-xyz").redirectUri())
        .isEqualTo(FAILURE_URI + "?error=email_conflict");
  }

  @Test
  @DisplayName("예기치 못한 예외는 server_error로 매핑한다")
  void completeLoginMapsUnexpectedExceptionToServerError() {
    when(webAuthService.completeLogin(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("boom"));

    assertThat(session.completeLogin(callbackRequest(), "auth-code", "state-xyz").redirectUri())
        .isEqualTo(FAILURE_URI + "?error=server_error");
  }

  @Test
  @DisplayName("세션 갱신은 refresh 쿠키를 읽어 새 access·refresh 쿠키를 돌려준다")
  void refreshRotatesSessionCookiesFromRefreshCookie() {
    when(webAuthService.refresh("old-web-refresh"))
        .thenReturn(new TokenPair("new-access-jwt", "new-refresh-token", 900));

    WebAuthCookieUpdate result =
        session.refresh(requestWithCookies(new Cookie("refresh_token", "old-web-refresh")));

    assertThat(result.setCookieHeaders())
        .anyMatch(
            c ->
                c.startsWith("access_token=new-access-jwt")
                    && c.contains("HttpOnly")
                    && c.contains("Path=/;"))
        .anyMatch(
            c ->
                c.startsWith("refresh_token=new-refresh-token")
                    && c.contains("HttpOnly")
                    && c.contains("Path=/api/auth"));
  }

  @Test
  @DisplayName("무효한 refresh는 예외를 그대로 전파해 Standard 에러로 응답하게 한다")
  void refreshPropagatesInvalidRefreshError() {
    when(webAuthService.refresh(any()))
        .thenThrow(new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

    assertThatThrownBy(() -> session.refresh(new MockHttpServletRequest()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("로그아웃은 refresh를 폐기하고 access·refresh·legacy session 쿠키를 정리한다")
  void logoutRevokesRefreshAndClearsCookies() {
    WebAuthCookieUpdate result =
        session.logout(requestWithCookies(new Cookie("refresh_token", "web-refresh")));

    verify(webAuthService).logout("web-refresh");
    assertThat(result.setCookieHeaders())
        .anyMatch(c -> c.startsWith("access_token=") && c.contains("Path=/;"))
        .anyMatch(c -> c.startsWith("refresh_token=") && c.contains("Path=/api/auth"))
        .anyMatch(c -> c.startsWith("session_token=") && c.contains("Path=/;"))
        .allMatch(c -> c.contains("Max-Age=0"))
        .hasSize(3);
    String legacySessionCookie =
        result.setCookieHeaders().stream()
            .filter(c -> c.startsWith("session_token="))
            .findFirst()
            .orElseThrow();
    assertThat(legacySessionCookie).doesNotContain("Domain=");
  }

  @Test
  @DisplayName("refresh 쿠키가 없어도 폐기 없이 세 쿠키를 정리한다")
  void logoutClearsCookiesWhenNoRefreshCookie() {
    WebAuthCookieUpdate result = session.logout(new MockHttpServletRequest());

    verify(webAuthService, never()).logout(any());
    assertThat(result.setCookieHeaders()).allMatch(c -> c.contains("Max-Age=0")).hasSize(3);
  }

  @Test
  @DisplayName("이미 무효한 refresh여도 세 쿠키를 정리한다(멱등)")
  void logoutClearsCookiesEvenWhenRefreshAlreadyInactive() {
    doThrow(new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID))
        .when(webAuthService)
        .logout("stale-refresh");

    WebAuthCookieUpdate result =
        session.logout(requestWithCookies(new Cookie("refresh_token", "stale-refresh")));

    assertThat(result.setCookieHeaders()).allMatch(c -> c.contains("Max-Age=0")).hasSize(3);
  }

  private static MockHttpServletRequest callbackRequest() {
    return requestWithCookies(
        new Cookie("oauth_state", "state-xyz"), new Cookie("oauth_pkce_verifier", "verifier-xyz"));
  }

  private static MockHttpServletRequest requestWithCookies(Cookie... cookies) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(cookies);
    return request;
  }

  private static AuthProperties authProperties() {
    return new AuthProperties(
        "test-only-momens-auth-jwt-secret-0123456789abcdef",
        Duration.ofMinutes(15),
        Duration.ofDays(14),
        new AuthProperties.Google(
            List.of("test-google-client-id.apps.googleusercontent.com"),
            "https://www.googleapis.com/oauth2/v3/certs"),
        new AuthProperties.Web(
            new AuthProperties.Web.GoogleOauth(
                "web-client-id",
                "web-client-secret",
                "http://localhost:8080/api/auth/google/callback",
                null,
                null,
                null,
                null),
            new AuthProperties.Web.Cookie(
                false, "Strict", "access_token", "refresh_token", "momens.works"),
            new AuthProperties.Web.Redirect(SUCCESS_URI, FAILURE_URI)));
  }
}
