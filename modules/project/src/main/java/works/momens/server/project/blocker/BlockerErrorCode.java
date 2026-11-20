package works.momens.server.project.blocker;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum BlockerErrorCode implements ErrorCode {
  BLOCKER_NOT_FOUND(404, "차단 사유를 찾을 수 없습니다."),
  BLOCKER_WORKSPACE_MISMATCH(400, "요청한 워크스페이스와 대상의 워크스페이스가 다릅니다.");

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
