package works.momens.server.mobile.board;

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
 * 필터는 태스크의 마감일이 {@link DueDateRange}에 정의된 조회 기간에 포함되는 경우에만 필터 조건을 충족한 것으로 판정합니다. 마감일이 지정되지 않은 태스크는
 * 필터 조건을 충족하지 않습니다. 조회 기간이 지정되지 않은 경우에는 마감일 필터를 적용하지 않습니다.
 *
 * <p>담당자 필터는 {@code assigneeIds}와 {@code unassigned}로 구성됩니다. 태스크의 담당자가 {@code assigneeIds}에 포함되어
 * 있거나, {@code unassigned}가 {@code true}이고 태스크에 담당자가 지정되지 않은 경우 필터 조건을 충족합니다. {@code assigneeIds}가
 * 비어 있고 {@code unassigned}가 활성화되지 않은 경우 담당자 필터를 적용하지 않습니다.
 *
 * <p>역할, 우선순위, 담당자 쿼리 파라미터에 빈 값을 전달하면 {@code null}로 변환되므로, 필터 생성 시 목록에서 {@code null} 원소를 제외합니다.
 */
record MobileTaskFilter(
    Set<TaskRole> roles,
    Set<TaskPriority> priorities,
    DueDateRange dueDateRange,
    Set<UUID> assigneeIds,
    boolean unassigned) {

  MobileTaskFilter {
    roles = withoutNulls(roles);
    priorities = withoutNulls(priorities);
    assigneeIds = withoutNulls(assigneeIds);
  }

  static MobileTaskFilter none() {
    return new MobileTaskFilter(Set.of(), Set.of(), null, Set.of(), false);
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
    return dueDateRange == null || dueDateRange.contains(task.dueDate());
  }

  private boolean matchesAssignee(BoardTask task) {
    if (assigneeIds.isEmpty() && !unassigned) {
      return true;
    }
    UUID assigneeId = task.assigneeId();
    return assigneeId == null ? unassigned : assigneeIds.contains(assigneeId);
  }

  private static <T> Set<T> withoutNulls(Collection<T> values) {
    if (values == null) {
      return Set.of();
    }
    return values.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
  }
}
