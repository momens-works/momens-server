package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 저장 상태를 정의하며 보드 그룹의 표시 순서와 라벨은 모바일 경계에서 결정합니다. */
public enum TaskStatus {
  BACKLOG("backlog"),
  TODO("todo"),
  IN_PROGRESS("in_progress"),
  DONE("done"),
  CANCELLED("cancelled");

  private final String value;

  TaskStatus(String value) {
    this.value = value;
  }

  public static Optional<TaskStatus> from(String value) {
    for (TaskStatus constant : values()) {
      if (constant.value.equals(value)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return value;
  }
}
