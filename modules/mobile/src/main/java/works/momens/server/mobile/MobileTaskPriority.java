package works.momens.server.mobile;

import java.util.Comparator;
import java.util.List;
import works.momens.server.project.task.TaskPriority;

/**
 * 모바일 응답의 우선순위 표기와 화면 표시 순서를 관리합니다. 허용 값 집합은 {@link TaskPriority}에서 관리하므로 중복 선언하지 않습니다.
 *
 * <p>저장값을 변환 없이 응답에 사용하며, 허용되지 않은 저장값은 기본값으로 대체하지 않고 예외를 발생시킵니다.
 *
 * <p>{@code DISPLAY_ORDER}는 브리프의 현재 우선순위 정렬에 사용하는 표시 순서입니다. enum 선언 순서가 변경되어도 정렬 결과가 유지되도록 별도 목록으로
 * 정의합니다.
 */
public final class MobileTaskPriority {

  private static final List<TaskPriority> DISPLAY_ORDER =
      List.of(TaskPriority.URGENT, TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW);

  private MobileTaskPriority() {}

  public static String key(String storedPriority) {
    return from(storedPriority).value();
  }

  public static Comparator<String> displayOrder() {
    return Comparator.comparingInt(storedPriority -> DISPLAY_ORDER.indexOf(from(storedPriority)));
  }

  private static TaskPriority from(String storedPriority) {
    return TaskPriority.from(storedPriority)
        .orElseThrow(
            () -> new IllegalStateException("저장된 priority가 허용된 값이 아닙니다: " + storedPriority));
  }
}
