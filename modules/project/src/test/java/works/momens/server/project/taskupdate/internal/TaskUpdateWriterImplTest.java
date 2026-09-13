package works.momens.server.project.taskupdate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.project.taskupdate.CreateTaskUpdateCommand;

@ExtendWith(MockitoExtension.class)
class TaskUpdateWriterImplTest {
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  @Mock private TaskUpdateRepository taskUpdateRepository;
  @InjectMocks private TaskUpdateWriterImpl writer;

  @Test
  @DisplayName("태스크 업데이트는 전달받은 소속으로 저장하고 kind를 정규화한다")
  void createsFromCommandAndNormalizesKind() {
    writer.create(
        new CreateTaskUpdateCommand(
            taskId, workspaceId, projectId, userId, " 내용 ", " Comment ", Map.of()));

    ArgumentCaptor<TaskUpdate> update = ArgumentCaptor.forClass(TaskUpdate.class);
    verify(taskUpdateRepository).save(update.capture());
    assertThat(update.getValue().getWorkspaceId()).isEqualTo(workspaceId);
    assertThat(update.getValue().getProjectId()).isEqualTo(projectId);
    assertThat(update.getValue().getBody()).isEqualTo("내용");
    assertThat(update.getValue().getKind()).isEqualTo("comment");
  }
}
