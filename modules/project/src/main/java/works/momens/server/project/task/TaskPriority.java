package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 저장되는 태스크 우선순위입니다. 화면 표시 순서는 조회하는 클라이언트 모듈에서 결정합니다. */
public enum TaskPriority {
  LOW("low"),
  MEDIUM("medium"),
  HIGH("high"),
  URGENT("urgent");

  private final String value;

  TaskPriority(String value) {
    this.value = value;
  }

  public static Optional<TaskPriority> from(String value) {
    for (TaskPriority constant : values()) {
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
