package works.momens.server.auth.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

/**
 * 웹 Authorization Code 로그인 use case. 모바일과 같은 코어(user upsert + 토큰 발급)를 공유하되 서버 주도 code 교환과
 * state/PKCE 핸드셰이크가 필요해 분리하고, {@link ClientType#WEB}으로 발급합니다(ADR-0003).
 */
@Service
@RequiredArgsConstructor
public class WebAuthService {

  private static final int TOKEN_BYTES = 32;

  private final GoogleOAuthClient googleOAuthClient;
  private final UserService userService;
  private final JwtTokenService jwtTokenService;
  private final SecureRandom secureRandom = new SecureRandom();

  /** state·PKCE를 생성하고 consent로 보낼 authorization URL을 만듭니다. */
  public LoginRedirect startLogin() {
    String state = randomToken();
    String codeVerifier = randomToken();
    String codeChallenge = codeChallenge(codeVerifier);
    String authorizationUrl = googleOAuthClient.authorizationUrl(state, codeChallenge);
    return new LoginRedirect(authorizationUrl, state, codeVerifier);
  }

  /**
   * 콜백의 state/PKCE 핸드셰이크를 검증하고 code를 교환해 WEB 세션 토큰을 발급합니다. state는 쿠키에 보관한 값과 상수시간 비교하며, 불일치·누락은
   * {@link AuthErrorCode#AUTH_OAUTH_STATE_INVALID}로 막습니다.
   */
  public TokenPair completeLogin(
      String code, String state, String stateCookie, String codeVerifier) {
    requireValidHandshake(code, state, stateCookie, codeVerifier);
    String googleAccessToken = googleOAuthClient.exchangeCode(code, codeVerifier);
    GoogleUserInfo user = googleOAuthClient.fetchUserInfo(googleAccessToken);
    UserProfile profile =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE,
            user.sub(),
            user.email(),
            displayName(user),
            user.picture());
    return jwtTokenService.issueTokenPair(profile.id(), ClientType.WEB, null);
  }

  private static void requireValidHandshake(
      String code, String state, String stateCookie, String codeVerifier) {
    if (isBlank(code)
        || isBlank(state)
        || isBlank(stateCookie)
        || isBlank(codeVerifier)
        || !MessageDigest.isEqual(
            state.getBytes(StandardCharsets.UTF_8), stateCookie.getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(AuthErrorCode.AUTH_OAUTH_STATE_INVALID);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** 모바일과 같은 코어로 refresh token을 회전합니다(전송수단만 쿠키). */
  public TokenPair refresh(String refreshToken) {
    return jwtTokenService.refresh(refreshToken);
  }

  /** refresh token을 폐기합니다. */
  public void logout(String refreshToken) {
    jwtTokenService.revoke(refreshToken);
  }

  private String randomToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String codeChallenge(String codeVerifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is not available", e);
    }
  }

  private static String displayName(GoogleUserInfo user) {
    if (user.name() == null || user.name().isBlank()) {
      return user.email();
    }
    return user.name();
  }

  /** {@link #startLogin()} 결과: consent URL과 콜백 검증에 필요한 state·PKCE verifier. */
  public record LoginRedirect(String authorizationUrl, String state, String codeVerifier) {}
}
