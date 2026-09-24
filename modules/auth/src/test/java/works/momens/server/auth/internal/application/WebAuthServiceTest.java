package works.momens.server.auth.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.auth.internal.google.GoogleOAuthClient;
import works.momens.server.auth.internal.google.GoogleUserInfo;
import works.momens.server.auth.internal.jwt.JwtTokenService;
import works.momens.server.auth.internal.jwt.TokenPair;
import works.momens.server.auth.internal.refresh.ClientType;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserIdentityProvider;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/** 웹 Authorization Code 로그인의 핸드셰이크 검증과 토큰 발급 오케스트레이션 단위 검증. */
class WebAuthServiceTest {

  private final GoogleOAuthClient googleOAuthClient = mock(GoogleOAuthClient.class);
  private final UserService userService = mock(UserService.class);
  private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
  private final WebAuthService service =
      new WebAuthService(googleOAuthClient, userService, jwtTokenService);

  @Test
  void completeLoginValidatesHandshakeThenIssuesWebTokens() {
    UUID userId = UUID.randomUUID();
    UserProfile profile =
        new UserProfile(
            userId, "hong@momens.works", "홍길동", null, "pic", Instant.now(), Instant.now());
    when(googleOAuthClient.exchangeCode("auth-code", "verifier")).thenReturn("google-access");
    when(googleOAuthClient.fetchUserInfo("google-access"))
        .thenReturn(new GoogleUserInfo("google-sub", "hong@momens.works", "홍길동", "pic"));
    when(userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "google-sub", "hong@momens.works", "홍길동", "pic"))
        .thenReturn(profile);
    when(jwtTokenService.issueTokenPair(userId, ClientType.WEB, null))
        .thenReturn(new TokenPair("access-jwt", "refresh-token", 900));

    TokenPair tokens = service.completeLogin("auth-code", "state-xyz", "state-xyz", "verifier");

    assertThat(tokens.accessToken()).isEqualTo("access-jwt");
    assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
  }

  @Test
  void completeLoginRejectsWhenStateMismatch() {
    assertHandshakeRejected("auth-code", "state-a", "state-b", "verifier");
  }

  @Test
  void completeLoginRejectsWhenStateMissing() {
    assertHandshakeRejected("auth-code", null, "state-xyz", "verifier");
  }

  @Test
  void completeLoginRejectsWhenStateCookieMissing() {
    assertHandshakeRejected("auth-code", "state-xyz", null, "verifier");
  }

  @Test
  void completeLoginRejectsWhenCodeMissing() {
    assertHandshakeRejected(null, "state-xyz", "state-xyz", "verifier");
  }

  @Test
  void completeLoginRejectsWhenCodeVerifierMissing() {
    assertHandshakeRejected("auth-code", "state-xyz", "state-xyz", null);
  }

  @Test
  void completeLoginRejectsWhenInputsBlank() {
    assertHandshakeRejected("  ", "state-xyz", "state-xyz", "verifier");
  }

  private void assertHandshakeRejected(
      String code, String state, String stateCookie, String codeVerifier) {
    assertThatThrownBy(() -> service.completeLogin(code, state, stateCookie, codeVerifier))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_OAUTH_STATE_INVALID);
    // 검증 실패 시 외부 호출·발급을 건드리지 않습니다.
    verifyNoInteractions(googleOAuthClient, userService, jwtTokenService);
  }
}
