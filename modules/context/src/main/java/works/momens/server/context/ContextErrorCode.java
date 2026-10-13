package works.momens.server.context;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum ContextErrorCode implements ErrorCode {
  CONTEXT_CROSS_WORKSPACE_LINK_NOT_ALLOWED(400, "서로 다른 워크스페이스의 대상은 연결할 수 없습니다."),
  CONTEXT_LINK_NOT_FOUND(404, "연결을 찾을 수 없습니다.");

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
