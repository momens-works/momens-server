package works.momens.server.project.taskupdate;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 태스크 업데이트의 종류입니다.
 *
 * <p>`task_updates.kind`의 CHECK 제약에서 허용하는 두 가지 값을 표현합니다. 종류를 지정하지 않으면 `COMMENT`를 기본값으로 사용하며, 이 기본값은
 * `TaskUpdate` 엔티티에서 적용합니다.
 */
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
