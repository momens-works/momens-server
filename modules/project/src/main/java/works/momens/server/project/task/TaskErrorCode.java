package works.momens.server.project.task;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

/**
 * task 도메인 에러 코드.
 *
 * <p>공통 코드를 재사용하지 않고 도메인 의미가 드러나는 코드를 이 경계가 소유합니다(docs/spec/api-response-error-codes.md). {@link
 * TaskReader}는 Optional만 반환하므로, 이 코드를 던질지는 호출하는 쪽이 결정합니다.
 */
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
