package works.momens.server.auth;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
  AUTH_GOOGLE_TOKEN_INVALID(401, "Google ID 토큰이 유효하지 않습니다."),
  AUTH_GOOGLE_EMAIL_NOT_VERIFIED(401, "Google 계정 이메일이 검증되지 않았습니다."),
  AUTH_REFRESH_TOKEN_INVALID(401, "Refresh token이 유효하지 않습니다."),
  AUTH_OAUTH_STATE_INVALID(400, "OAuth state가 유효하지 않습니다."),
  AUTH_OAUTH_EXCHANGE_FAILED(502, "Google OAuth 코드 교환에 실패했습니다.");

  private final int status;
  private final String defaultMessage;

  @Override
  public String code() {
    return name();
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String defaultMessage() {
    return defaultMessage;
  }
}
