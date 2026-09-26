package works.momens.server.mobile;

import java.util.Arrays;
import works.momens.server.project.task.TaskPriority;

/**
 * 저장된 태스크 priority를 모바일 응답 값으로 mapping하는 single source of truth입니다. `urgent`는 web에서 사용하는 제품 값이며,
 * 앱에서 urgent priority를 지원하기 전까지 모바일 응답에서는 `high`로 표시합니다. 선언 순서가 priority 정렬 순서(높은 순)이므로, 브리프의 현재
 * priority를 선택할 때도 이 순서를 사용합니다(MOM-67).
 *
 * <p>저장값은 DB CHECK 제약이 4종(low, medium, high, urgent)만 허용하므로 그 밖의 값은 불변식이 깨진 경우입니다. 기본값으로 대체하지 않고
 * IllegalStateException으로 처리합니다. 담당자 조회가 USER_NOT_FOUND로 처리하는 것과 같은 방식입니다.
 */
public enum MobileTaskPriority {
  HIGH(TaskPriority.HIGH),
  MEDIUM(TaskPriority.MEDIUM),
  LOW(TaskPriority.LOW);

  private final TaskPriority taskPriority;

  MobileTaskPriority(TaskPriority taskPriority) {
    this.taskPriority = taskPriority;
  }

  /** 응답으로 반환하는 모바일 priority 값입니다. */
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
