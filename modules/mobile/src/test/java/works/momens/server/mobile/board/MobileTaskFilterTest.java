package works.momens.server.mobile.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

class MobileTaskFilterTest {

  private static final UUID ASSIGNEE_ID = UUID.randomUUID();
  private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 27);

  @Test
  void noneMatchesTaskWithoutRolesAssigneeAndDueDate() {
    assertThat(MobileTaskFilter.none().matches(task(List.of(), "low", null, null))).isTrue();
  }

  @Test
  void valuesInSameFilterAreCombinedWithOr() {
    MobileTaskFilter filter =
        new MobileTaskFilter(
            Set.of(TaskRole.DESIGN, TaskRole.PM),
            Set.of(TaskPriority.HIGH, TaskPriority.URGENT),
            null,
            null,
            false);

    assertThat(filter.matches(task(List.of("pm", "backend"), "urgent", null, null))).isTrue();
    assertThat(filter.matches(task(List.of("backend"), "urgent", null, null))).isFalse();
  }

  @Test
  void differentFiltersAreCombinedWithAnd() {
    MobileTaskFilter filter =
        new MobileTaskFilter(
            Set.of(TaskRole.DESIGN),
            Set.of(TaskPriority.HIGH),
            new DueDateRange(DUE_DATE, DUE_DATE),
            Set.of(ASSIGNEE_ID),
            false);

    assertThat(filter.matches(task(List.of("design"), "high", ASSIGNEE_ID, DUE_DATE))).isTrue();
    assertThat(filter.matches(task(List.of("design"), "low", ASSIGNEE_ID, DUE_DATE))).isFalse();
    assertThat(filter.matches(task(List.of("design"), "high", UUID.randomUUID(), DUE_DATE)))
        .isFalse();
  }

  @Test
  void dueDateRangeIncludesBothEndsAndExcludesTaskWithoutDueDate() {
    LocalDate to = DUE_DATE.plusDays(6);
    MobileTaskFilter range = dueDateFilter(DUE_DATE, to);

    assertThat(range.matches(task(List.of(), "low", null, DUE_DATE))).isTrue();
    assertThat(range.matches(task(List.of(), "low", null, to))).isTrue();
    assertThat(range.matches(task(List.of(), "low", null, DUE_DATE.minusDays(1)))).isFalse();
    assertThat(range.matches(task(List.of(), "low", null, to.plusDays(1)))).isFalse();
    assertThat(range.matches(task(List.of(), "low", null, null))).isFalse();

    MobileTaskFilter fromOnly = dueDateFilter(DUE_DATE, null);
    assertThat(fromOnly.matches(task(List.of(), "low", null, DUE_DATE.plusYears(1)))).isTrue();
    assertThat(fromOnly.matches(task(List.of(), "low", null, DUE_DATE.minusDays(1)))).isFalse();

    MobileTaskFilter toOnly = dueDateFilter(null, DUE_DATE);
    assertThat(toOnly.matches(task(List.of(), "low", null, DUE_DATE.minusYears(1)))).isTrue();
    assertThat(toOnly.matches(task(List.of(), "low", null, DUE_DATE.plusDays(1)))).isFalse();
  }

  @Test
  void unassignedMatchesOnlyTaskWithoutAssignee() {
    MobileTaskFilter filter = new MobileTaskFilter(Set.of(), Set.of(), null, null, true);

    assertThat(filter.matches(task(List.of(), "low", null, null))).isTrue();
    assertThat(filter.matches(task(List.of(), "low", ASSIGNEE_ID, null))).isFalse();
  }

  @Test
  void selectedAssigneesAreCombinedWithOr() {
    UUID otherAssigneeId = UUID.randomUUID();
    MobileTaskFilter filter =
        new MobileTaskFilter(Set.of(), Set.of(), null, Set.of(ASSIGNEE_ID, otherAssigneeId), false);

    assertThat(filter.matches(task(List.of(), "low", ASSIGNEE_ID, null))).isTrue();
    assertThat(filter.matches(task(List.of(), "low", otherAssigneeId, null))).isTrue();
    assertThat(filter.matches(task(List.of(), "low", UUID.randomUUID(), null))).isFalse();
    assertThat(filter.matches(task(List.of(), "low", null, null))).isFalse();
  }

  @Test
  void selectedAssigneesAndUnassignedAreCombinedWithOr() {
    MobileTaskFilter filter =
        new MobileTaskFilter(Set.of(), Set.of(), null, Set.of(ASSIGNEE_ID), true);

    assertThat(filter.matches(task(List.of(), "low", ASSIGNEE_ID, null))).isTrue();
    assertThat(filter.matches(task(List.of(), "low", null, null))).isTrue();
    assertThat(filter.matches(task(List.of(), "low", UUID.randomUUID(), null))).isFalse();
  }

  private static MobileTaskFilter dueDateFilter(LocalDate from, LocalDate to) {
    return new MobileTaskFilter(Set.of(), Set.of(), new DueDateRange(from, to), null, false);
  }

  private static BoardTask task(
      List<String> roles, String priority, UUID assigneeId, LocalDate dueDate) {
    return new BoardTask(
        UUID.randomUUID(),
        "title",
        "todo",
        priority,
        roles,
        assigneeId,
        dueDate,
        Instant.parse("2026-10-01T00:00:00Z"));
  }
}
