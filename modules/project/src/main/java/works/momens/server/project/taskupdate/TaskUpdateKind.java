package works.momens.server.project.taskupdate;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

public enum TaskUpdateKind {
  COMMENT("comment"),
  UPDATE("update");

  private final String value;

  TaskUpdateKind(String value) {
    this.value = value;
  }

  public static Optional<TaskUpdateKind> from(String value) {
    for (TaskUpdateKind constant : values()) {
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
