package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.TaskStatus;

/**
 * 진행률 계산 규칙 검증.
 *
 * <p>DB 없이 계산 규칙(분모에 드는 상태, 소수점 버림, 없는 project 처리)만 확인합니다. 실제 태스크 조회까지 포함한 동작은 app 통합테스트에서 전체 컨텍스트로
 * 검증합니다. task 조회 구현이 package-private라 project 슬라이스에서 조립하기 어렵기 때문입니다({@code TaskWriterImplTest}와 같은
 * 제약).
 */
@ExtendWith(MockitoExtension.class)
class TaskProgressReaderImplTest {

  private static final UUID PROJECT_ID = UUID.randomUUID();

  @Mock private ProjectReader projectReader;
  @Mock private TaskRepository taskRepository;
  @InjectMocks private TaskProgressReaderImpl taskProgressReader;

  @Test
  void progressOfCountsDoneAgainstEveryStatusExceptCancelled() {
    liveProject();
    when(taskRepository.countByStatus(eq(PROJECT_ID), any())).thenReturn(List.of());

    taskProgressReader.progressOf(PROJECT_ID);

    // cancelled를 조회 대상에 넣지 않아서 분모에서 빠진다. 이 단언이 그 설계 의도를 고정한다.
    // 상태가 추가되면 PROGRESS_STATUSES도 함께 변경되어 이 테스트가 실패한다.
    // 새 상태가 계산 대상에서 빠지는 것을 바로 확인할 수 있으므로,
    // 분모에 포함할지 결정한 뒤 기대값을 수정한다.
    ArgumentCaptor<List<String>> statuses = ArgumentCaptor.captor();
    verify(taskRepository).countByStatus(eq(PROJECT_ID), statuses.capture());
    assertThat(statuses.getValue()).containsExactly("backlog", "todo", "in_progress", "done");
    assertThat(statuses.getValue()).doesNotContain(TaskStatus.CANCELLED.value());
  }

  @Test
  void progressOfReturnsDoneRatioOfCountedStatuses() {
    liveProject();
    stubTasks(2, 81);

    assertThat(taskProgressReader.progressOf(PROJECT_ID)).hasValue(2);
  }

  @Test
  void progressOfReturnsHundredOnlyWhenEveryCountedTaskIsDone() {
    liveProject();
    stubTasks(5, 0);

    assertThat(taskProgressReader.progressOf(PROJECT_ID)).hasValue(100);
  }

  @Test
  void progressOfDropsTheFractionRatherThanRoundingUp() {
    liveProject();
    // 199/200은 99.5라 반올림하면 100이 된다. 미완료가 있는데 100을 내리지 않도록 버린다.
    stubTasks(199, 1);

    assertThat(taskProgressReader.progressOf(PROJECT_ID)).hasValue(99);
  }

  @Test
  void progressOfIsZeroForProjectWithoutTasks() {
    liveProject();
    when(taskRepository.countByStatus(eq(PROJECT_ID), any())).thenReturn(List.of());

    assertThat(taskProgressReader.progressOf(PROJECT_ID)).hasValue(0);
  }

  @Test
  void progressOfIsEmptyForMissingProjectAndDoesNotReadTasks() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.empty());

    assertThat(taskProgressReader.progressOf(PROJECT_ID)).isEqualTo(OptionalInt.empty());
  }

  private void liveProject() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(UUID.randomUUID()));
  }

  private void stubTasks(int done, int notDone) {
    // 개수가 0인 상태는 집계 결과에 없다. repository 계약과 같게 만든다.
    List<StatusCount> counts = new ArrayList<>();
    if (done > 0) {
      counts.add(new StatusCount(TaskStatus.DONE.value(), done));
    }
    if (notDone > 0) {
      counts.add(new StatusCount(TaskStatus.TODO.value(), notDone));
    }
    when(taskRepository.countByStatus(eq(PROJECT_ID), any())).thenReturn(counts);
  }
}
