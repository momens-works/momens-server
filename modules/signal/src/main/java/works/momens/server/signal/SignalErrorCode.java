package works.momens.server.signal;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum SignalErrorCode implements ErrorCode {
  SIGNAL_NOT_FOUND(404, "시그널을 찾을 수 없습니다."),
  SIGNAL_INVALID_STATE(409, "이미 다른 action으로 처리된 시그널입니다.");

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
