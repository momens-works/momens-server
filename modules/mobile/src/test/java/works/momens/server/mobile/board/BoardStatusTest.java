package works.momens.server.mobile.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import works.momens.server.project.task.TaskStatus;

class BoardStatusTest {

  @Test
  void coversEveryTaskStatus() {
    assertThat(Arrays.stream(BoardStatus.values()).map(BoardStatus::taskStatus))
        .containsExactlyInAnyOrder(TaskStatus.values());
  }
}
