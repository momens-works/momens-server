package works.momens.server.project.task;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskCommandTest {

  @Test
  void createRequiresStatus() {
    assertThatIllegalArgumentException().isThrownBy(() -> createCommand(null));
  }

  @Test
  void patchRequiresValueWhenNonNullableFieldIsSet() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> patchCommand(null, true, TaskStatus.TODO, false, TaskPriority.MEDIUM, false));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> patchCommand("제목", false, null, true, TaskPriority.MEDIUM, false));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> patchCommand("제목", false, TaskStatus.TODO, false, null, true));
  }

  @Test
  void acceptsTitleAtMaxLength() {
    String atMaxLength = "가".repeat(TaskPolicy.TITLE_MAX_LENGTH);
    assertThatNoException().isThrownBy(() -> createCommand(TaskStatus.TODO, atMaxLength));
    assertThatNoException()
        .isThrownBy(() ->
            patchCommand(atMaxLength, true, TaskStatus.TODO, false, TaskPriority.MEDIUM, false));
    assertThatNoException().isThrownBy(() -> updateCommand(atMaxLength));
  }

  @Test
  void rejectsTitleOverMaxLength() {
    String overMaxLength = "가".repeat(TaskPolicy.TITLE_MAX_LENGTH + 1);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> createCommand(TaskStatus.TODO, overMaxLength));
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            patchCommand(overMaxLength, true, TaskStatus.TODO, false, TaskPriority.MEDIUM, false));
    assertThatIllegalArgumentException().isThrownBy(() -> updateCommand(overMaxLength));
  }

  private static CreateTaskCommand createCommand(TaskStatus status) {
    return createCommand(status, "제목");
  }

  private static CreateTaskCommand createCommand(TaskStatus status, String title) {
    return new CreateTaskCommand(
        UUID.randomUUID(),
        UUID.randomUUID(),
        title,
        null,
        status,
        null,
        TaskPriority.MEDIUM,
        null,
        null,
        null,
        TaskOrigin.MANUAL,
        null);
  }

  private static UpdateTaskCommand updateCommand(String title) {
    return new UpdateTaskCommand(
        UUID.randomUUID(),
        title,
        null,
        null,
        TaskPriority.MEDIUM,
        TaskStatus.TODO,
        null,
        List.of(),
        null);
  }

  private static PatchTaskCommand patchCommand(
      String title,
      boolean titleSet,
      TaskStatus status,
      boolean statusSet,
      TaskPriority priority,
      boolean prioritySet) {
    return new PatchTaskCommand(
        UUID.randomUUID(),
        title,
        titleSet,
        null,
        false,
        status,
        statusSet,
        priority,
        prioritySet,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }
}
