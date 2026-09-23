package works.momens.server.mobile;

import java.util.Arrays;
import java.util.Optional;
import works.momens.server.project.task.TaskPriority;

/**
 * 저장된 태스크 priority를 모바일 표기로 해석하는 단일 출처. 모바일 enum은 low, medium, high 3종이므로 레거시에만 있는 urgent는 high로
 * 취급합니다(2026-07-06 가결정). 선언 순서가 곧 우선순위 정렬 순서(높은 순)이므로, 브리프의 현재 우선순위를 고를 때도 이 순서를 사용합니다(MOM-67).
 *
 * <p>저장값은 DB CHECK 제약이 4종(low, medium, high, urgent)만 허용하므로 그 밖의 값은 불변식이 깨진 경우입니다. 기본값으로 대체하지 않고
 * IllegalStateException으로 처리합니다. 담당자 조회가 USER_NOT_FOUND로 처리하는 것과 같은 방식입니다.
 */
public enum MobilePriority {
  HIGH(TaskPriority.HIGH),
  MEDIUM(TaskPriority.MEDIUM),
  LOW(TaskPriority.LOW);

  private final TaskPriority taskPriority;

  MobilePriority(TaskPriority taskPriority) {
    this.taskPriority = taskPriority;
  }

  /** 응답으로 반환하는 모바일 priority 값입니다. */
  public String key() {
    return taskPriority.value();
  }

  /** 저장할 때 사용할 도메인 우선순위를 반환합니다. */
  public TaskPriority taskPriority() {
    return taskPriority;
  }

  /** 요청으로 받은 모바일 priority 값을 해석합니다. 모바일에서 허용하지 않는 값이면 빈 `Optional`을 반환합니다. */
  public static Optional<MobilePriority> from(String key) {
    for (MobilePriority priority : values()) {
      if (priority.key().equals(key)) {
        return Optional.of(priority);
      }
    }
    return Optional.empty();
  }

  /** 저장된 priority 문자열을 해석합니다. urgent는 high로 취급합니다. */
  public static MobilePriority fromStored(String storedPriority) {
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
