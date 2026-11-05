package works.momens.server.mobile;

import java.util.Comparator;
import works.momens.server.project.task.TaskPriority;

/**
 * 모바일 응답의 우선순위 표기와 화면 표시 순서를 관리합니다. 허용 값 집합은 {@link TaskPriority}에서 관리하므로 중복 선언하지 않습니다.
 *
 * <p>저장값을 변환 없이 응답에 사용하며, 허용되지 않은 저장값은 기본값으로 대체하지 않고 예외를 발생시킵니다.
 *
 * <p>{@code rank}는 브리프의 현재 우선순위를 정렬할 때 사용하는 표시 순서입니다. enum 선언 순서에 의존하지 않으며, {@link TaskPriority}에
 * 새로운 값이 추가되면 switch 식의 누락된 분기를 컴파일 단계에서 확인할 수 있습니다.
 */
public final class MobileTaskPriority {

  private MobileTaskPriority() {}

  public static String key(String storedPriority) {
    return from(storedPriority).value();
  }

  public static Comparator<String> displayOrder() {
    return Comparator.comparingInt(storedPriority -> rank(from(storedPriority)));
  }

  private static int rank(TaskPriority priority) {
    return switch (priority) {
      case URGENT -> 0;
      case HIGH -> 1;
      case MEDIUM -> 2;
      case LOW -> 3;
    };
  }

  private static TaskPriority from(String storedPriority) {
    return TaskPriority.from(storedPriority)
        .orElseThrow(
            () -> new IllegalStateException("저장된 priority가 허용된 값이 아닙니다: " + storedPriority));
  }
}
