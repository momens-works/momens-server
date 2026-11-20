package works.momens.server.mobile.board;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/**
 * 모바일 태스크 보드 조회에 사용하는 필터 조건과 판정 규칙을 정의합니다.
 *
 * <p>같은 필터에서 선택한 값은 OR 조건으로, 서로 다른 필터는 AND 조건으로 결합합니다. 역할과 우선순위 목록이 비어 있으면 해당 조건을 적용하지 않습니다. 마감일
 * 필터는 태스크의 마감일이 선택한 날짜와 일치하는 경우에만 통과하며, 마감일이 지정되지 않은 태스크는 제외합니다.
 *
 * <p>{@code unassigned}가 {@code true}이면 담당자가 지정되지 않은 태스크만 통과합니다. {@code assigneeId}와 {@code
 * unassigned=true}를 함께 전달한 요청은 컨트롤러에서 거부하므로 두 조건이 동시에 적용되지 않습니다.
 *
 * <p>쿼리 파라미터에 빈 값으로 전달된 역할과 우선순위는 {@code null}로 변환되므로, 필터 생성 시 {@code null} 원소를 제외합니다.
 */
record MobileTaskFilter(
    Set<TaskRole> roles,
    Set<TaskPriority> priorities,
    LocalDate dueDate,
    UUID assigneeId,
    boolean unassigned) {

  MobileTaskFilter {
    roles = withoutNulls(roles);
    priorities = withoutNulls(priorities);
  }

  static MobileTaskFilter none() {
    return new MobileTaskFilter(Set.of(), Set.of(), null, null, false);
  }

  boolean matches(BoardTask task) {
    return matchesRoles(task)
        && matchesPriorities(task)
        && matchesDueDate(task)
        && matchesAssignee(task);
  }

  private boolean matchesRoles(BoardTask task) {
    return roles.isEmpty() || roles.stream().anyMatch(role -> task.roles().contains(role.value()));
  }

  private boolean matchesPriorities(BoardTask task) {
    return priorities.isEmpty()
        || priorities.stream().anyMatch(priority -> priority.value().equals(task.priority()));
  }

  private boolean matchesDueDate(BoardTask task) {
    return dueDate == null || dueDate.equals(task.dueDate());
  }

  private boolean matchesAssignee(BoardTask task) {
    if (unassigned) {
      return task.assigneeId() == null;
    }
    return assigneeId == null || assigneeId.equals(task.assigneeId());
  }

  private static <T> Set<T> withoutNulls(Collection<T> values) {
    if (values == null) {
      return Set.of();
    }
    return values.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
  }
}
