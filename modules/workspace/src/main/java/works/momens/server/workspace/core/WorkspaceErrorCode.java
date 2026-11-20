package works.momens.server.workspace.core;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum WorkspaceErrorCode implements ErrorCode {
  WORKSPACE_NOT_FOUND(404, "워크스페이스를 찾을 수 없습니다."),
  WORKSPACE_INVALID_SLUG(400, "사용할 수 없는 slug 형식입니다."),
  WORKSPACE_RESERVED_SLUG(400, "예약어로 지정된 slug입니다."),
  WORKSPACE_SLUG_ALREADY_EXISTS(409, "이미 사용 중인 slug입니다.");

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
