package works.momens.server.user;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
  USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다."),
  USER_EMAIL_AMBIGUOUS(409, "같은 이메일을 사용하는 사용자가 여러 명입니다.");

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
