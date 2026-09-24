package works.momens.server.auth.internal.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import works.momens.server.auth.AuthTokens;
import works.momens.server.auth.MobileAuthService;
import works.momens.server.auth.internal.google.GoogleIdTokenVerifier;
import works.momens.server.auth.internal.google.GoogleUserInfo;
import works.momens.server.auth.internal.jwt.JwtTokenService;
import works.momens.server.auth.internal.jwt.TokenPair;
import works.momens.server.auth.internal.refresh.ClientType;
import works.momens.server.user.UserIdentityProvider;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/** 모바일 로그인과 refresh/logout use case orchestration. */
@Service
@RequiredArgsConstructor
class MobileAuthServiceImpl implements MobileAuthService {

  private final GoogleIdTokenVerifier googleIdTokenVerifier;
  private final UserService userService;
  private final JwtTokenService jwtTokenService;

  /**
   * Google ID 토큰 검증은 외부 호출이 섞일 수 있어 트랜잭션 밖에서 먼저 수행합니다. 이후 user upsert와 token 발급은 각자의 트랜잭션에서 처리하며,
   * 검증 실패 시 DB를 건드리지 않습니다.
   */
  @Override
  public AuthTokens loginWithGoogleToken(String idToken, String device) {
    GoogleUserInfo googleUser = googleIdTokenVerifier.verify(idToken);
    UserProfile user =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE,
            googleUser.sub(),
            googleUser.email(),
            displayName(googleUser),
            googleUser.picture());
    return toTokens(jwtTokenService.issueTokenPair(user.id(), ClientType.MOBILE, device));
  }

  @Override
  public AuthTokens refresh(String refreshToken) {
    return toTokens(jwtTokenService.refresh(refreshToken));
  }

  @Override
  public void logout(String refreshToken) {
    jwtTokenService.revoke(refreshToken);
  }

  private static AuthTokens toTokens(TokenPair tokenPair) {
    return new AuthTokens(
        tokenPair.accessToken(), tokenPair.refreshToken(), tokenPair.expiresInSeconds());
  }

  private static String displayName(GoogleUserInfo googleUser) {
    if (googleUser.name() == null || googleUser.name().isBlank()) {
      return googleUser.email();
    }
    return googleUser.name();
  }
}
