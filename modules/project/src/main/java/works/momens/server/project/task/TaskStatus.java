package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 태스크 상태.
 *
 * <p>저장 값은 `tasks.status`의 DB CHECK 제약과 같은 5종이다. 상태 집합을 코드에서 소유하는 곳이라, 상태를 늘리거나 줄일 때 마이그레이션과 이
 * enum을 함께 바꾼다.
 *
 * <p>어떤 상태를 어떻게 보여줄지는 표면이 정한다. 보드 그룹의 노출 순서와 라벨은 mobile의 {@code MobileTaskStatus}가 따로 소유한다.
 */
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
