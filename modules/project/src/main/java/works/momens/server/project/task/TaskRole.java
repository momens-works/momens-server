package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

public enum TaskRole {
  PM("pm"),
  DESIGN("design"),
  BACKEND("backend"),
  FRONTEND("frontend");

  private final String value;

  TaskRole(String value) {
    this.value = value;
  }

  public static Optional<TaskRole> from(String value) {
    for (TaskRole constant : values()) {
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
