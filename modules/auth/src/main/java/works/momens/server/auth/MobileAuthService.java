package works.momens.server.auth;

/**
 * 모바일 인증 public API. 토큰을 Bearer로 전송하는 클라이언트가 쓰며, 전송수단만 다를 뿐 발급·회전 코어는 웹과 같습니다(ADR-0003).
 *
 * <p>인증 실패는 {@link AuthErrorCode}의 {@code BusinessException}으로 전파되며, 표면 모듈은 이를 잡지 않고 공통 예외 핸들러가
 * Standard 에러 body로 변환합니다.
 */
public interface MobileAuthService {

  /**
   * Google ID 토큰을 검증하고 MOBILE 세션 토큰을 발급합니다. 토큰이 유효하지 않으면 {@link
   * AuthErrorCode#AUTH_GOOGLE_TOKEN_INVALID}입니다.
   */
  AuthTokens loginWithGoogleToken(String idToken, String device);

  /** refresh token을 회전합니다. 무효·폐기된 토큰은 {@link AuthErrorCode#AUTH_REFRESH_TOKEN_INVALID}입니다. */
  AuthTokens refresh(String refreshToken);

  /** refresh token을 폐기합니다. 토큰이 비어 있거나 저장되어 있지 않은 경우, 이미 폐기되었거나 만료된 경우에도 예외를 던지지 않고 정상적으로 종료합니다. */
  void logout(String refreshToken);
}
