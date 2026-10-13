package works.momens.server.mobile;

import java.util.Arrays;
import works.momens.server.project.task.TaskPriority;

/**
 * 저장된 urgent는 앱 지원 전까지 high로 표시합니다. enum 선언 순서는 우선순위 정렬에도 사용합니다. 알 수 없는 저장값은 기본값으로 숨기지 않고 실패시킵니다.
 */
public enum MobileTaskPriority {
  HIGH(TaskPriority.HIGH),
  MEDIUM(TaskPriority.MEDIUM),
  LOW(TaskPriority.LOW);

  private final TaskPriority taskPriority;

  MobileTaskPriority(TaskPriority taskPriority) {
    this.taskPriority = taskPriority;
  }

  public String key() {
    return taskPriority.value();
  }

  /** 저장된 priority 문자열을 해석합니다. urgent는 high로 취급합니다. */
  public static MobileTaskPriority fromStored(String storedPriority) {
    TaskPriority stored =
        TaskPriority.from(storedPriority)
            .orElseThrow(
                () -> new IllegalStateException("저장된 priority가 허용된 값이 아닙니다: " + storedPriority));
    if (stored == TaskPriority.URGENT) {
      return HIGH;
    }
    return Arrays.stream(values())
        .filter(priority -> priority.taskPriority == stored)
        .findFirst()
        .orElseThrow();
  }
}
