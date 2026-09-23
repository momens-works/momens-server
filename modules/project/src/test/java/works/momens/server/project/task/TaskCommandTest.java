package works.momens.server.project.task;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

  private static CreateTaskCommand createCommand(TaskStatus status) {
    return new CreateTaskCommand(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "제목",
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
        false);
  }
}
