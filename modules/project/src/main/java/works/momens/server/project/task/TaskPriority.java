package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 저장 우선순위입니다. 모바일의 urgent → high 표기 변환은 모바일 경계에서 처리합니다. */
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
