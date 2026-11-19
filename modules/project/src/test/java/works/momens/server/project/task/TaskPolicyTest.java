package works.momens.server.project.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskPolicyTest {

  @Test
  void onlyCreatorCanDelete() {
    UUID creatorId = UUID.randomUUID();

    assertThat(TaskPolicy.canDelete(creatorId, creatorId)).isTrue();
    assertThat(TaskPolicy.canDelete(creatorId, UUID.randomUUID())).isFalse();
    assertThat(TaskPolicy.canDelete(null, creatorId)).isFalse();
  }
}
