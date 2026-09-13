package works.momens.server.web.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskScope;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@ExtendWith(MockitoExtension.class)
class TaskWriteServiceTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID TASK_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private TaskWriter taskWriter;
  @Mock private TaskReader taskReader;
  @Mock private ProjectReader projectReader;
  @Mock private WorkspaceReader workspaceReader;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  private TaskWriteService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskWriteService(
            taskWriter,
            taskReader,
            projectReader,
            new WorkspaceAccessChecker(workspaceReader, workspaceMembershipReader));
  }

  @Test
  void createNormalizesLegacyAliasesBeforeCallingDomain() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));

    service.create(
        PROJECT_ID, USER_ID, "제목", "", "In-Progress", null, "MED", null, LocalDate.of(2026, 8, 31));

    ArgumentCaptor<CreateTaskCommand> captor = ArgumentCaptor.captor();
    verify(taskWriter).create(captor.capture());
    assertThat(captor.getValue().workspaceId()).isEqualTo(WORKSPACE_ID);
    assertThat(captor.getValue().description()).isNull();
    assertThat(captor.getValue().status()).isEqualTo("in_progress");
    assertThat(captor.getValue().priority()).isEqualTo("medium");
  }

  @Test
  void patchPreservesLegacyEmptyAndNullSemantics() {
    when(taskReader.findScope(TASK_ID))
        .thenReturn(Optional.of(new TaskScope(WORKSPACE_ID, PROJECT_ID)));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));

    service.update(
        TASK_ID,
        USER_ID,
        "",
        true,
        null,
        false,
        "progress",
        true,
        "",
        true,
        null,
        false,
        null,
        false,
        null,
        false);

    ArgumentCaptor<PatchTaskCommand> captor = ArgumentCaptor.captor();
    verify(taskWriter).patch(captor.capture());
    assertThat(captor.getValue().titleSet()).isFalse();
    assertThat(captor.getValue().status()).isEqualTo("in_progress");
    assertThat(captor.getValue().statusSet()).isTrue();
    assertThat(captor.getValue().prioritySet()).isFalse();

    assertThatThrownBy(
            () ->
                service.update(
                    TASK_ID, USER_ID, null, false, null, false, null, true, null, false, null,
                    false, null, false, null, false))
        .isInstanceOf(BusinessException.class);
  }
}
