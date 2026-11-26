package works.momens.server.project.task;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum TaskErrorCode implements ErrorCode {
  TASK_NOT_FOUND(404, "작업을 찾을 수 없습니다."),
  TASK_CHECKLIST_ITEM_NOT_FOUND(404, "완료 기준 항목을 찾을 수 없습니다.");

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
