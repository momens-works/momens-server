package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 태스크의 우선순위입니다.
 *
 * <p>`tasks.priority`의 CHECK 제약에서 허용하는 네 가지 값을 표현합니다. 모바일에서는 이 중 세 가지 값만 허용하므로 `MobilePriority`에서
 * 범위를 별도로 제한합니다. 우선순위를 지정하지 않은 태스크는 `MEDIUM`을 기본값으로 사용하며, 이 기본값은 `Task` 엔티티에서 적용합니다.
 */
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
