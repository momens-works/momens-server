package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.milestone.MilestoneDirectory;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@ExtendWith(MockitoExtension.class)
class TaskWriterImplTest {

  @Mock private TaskRepository taskRepository;
  @Mock private MilestoneDirectory milestoneDirectory;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private LabelAllocator labelAllocator;
  @Mock private OutboxAppender outboxAppender;
  @InjectMocks private TaskWriterImpl taskWriter;

  @Test
  void createUsesCommandPolicyAndAppendsCreatedEvent() {
    UUID projectId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(labelAllocator.allocateMomLabel(workspaceId)).thenReturn("MOM-0001");

    TaskSnapshot created =
        taskWriter.create(
            CreateTaskCommand.manual(projectId, workspaceId, "권한 요청 점검", "backend", "high"));

    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    verify(taskRepository).save(captor.capture());
    Task saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo("todo");
    assertThat(saved.getPriority()).isEqualTo("high");
    assertThat(saved.getRole()).isEqualTo("backend");
    assertThat(created.id()).isEqualTo(saved.getId());

    Map<String, Object> payload = new HashMap<>();
    payload.put("origin_type", "manual");
    payload.put("origin_signal_id", null);
    verify(outboxAppender)
        .append(
            eq(workspaceId),
            eq("task"),
            eq(created.id().toString()),
            eq("task.created"),
            eq(payload));
  }

  @Test
  void createDefaultsMissingPriorityAndKeepsSignalOrigin() {
    UUID projectId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID signalId = UUID.randomUUID();
    when(labelAllocator.allocateMomLabel(workspaceId)).thenReturn("MOM-0002");

    TaskSnapshot created =
        taskWriter.create(
            CreateTaskCommand.fromSignal(projectId, workspaceId, "제목", "pm", null, signalId));

    assertThat(created.priority()).isEqualTo("medium");
    verify(outboxAppender)
        .append(
            workspaceId,
            "task",
            created.id().toString(),
            "task.created",
            Map.of("origin_type", "signal", "origin_signal_id", signalId.toString()));
  }

  @Test
  void createStoresAllCanonicalFields() {
    UUID projectId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID milestoneId = UUID.randomUUID();
    UUID assigneeId = UUID.randomUUID();
    LocalDate dueDate = LocalDate.of(2026, 8, 31);
    when(labelAllocator.allocateMomLabel(workspaceId)).thenReturn("MOM-0002");
    when(milestoneDirectory.existsInProject(milestoneId, projectId)).thenReturn(true);
    when(workspaceMembershipReader.roleOf(workspaceId, assigneeId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));

    TaskSnapshot created =
        taskWriter.create(
            new CreateTaskCommand(
                projectId,
                workspaceId,
                "제목",
                "설명",
                "backlog",
                null,
                "medium",
                milestoneId,
                assigneeId,
                dueDate,
                TaskOrigin.MANUAL,
                null));

    assertThat(created.description()).isEqualTo("설명");
    assertThat(created.status()).isEqualTo("backlog");
    assertThat(created.milestoneId()).isEqualTo(milestoneId);
    assertThat(created.assigneeId()).isEqualTo(assigneeId);
    assertThat(created.dueDate()).isEqualTo(dueDate);
  }

  @Test
  void patchAppliesOnlyFieldsMarkedAsSet() {
    UUID projectId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    Task task =
        Task.create(
            CreateTaskCommand.manual(projectId, workspaceId, "기존", "pm", "high"), "MOM-0003");
    when(taskRepository.findByIdAndDeletedAtIsNull(task.getId()))
        .thenReturn(java.util.Optional.of(task));

    taskWriter.patch(
        new PatchTaskCommand(
            task.getId(),
            "새 제목",
            false,
            "설명",
            true,
            "done",
            true,
            "low",
            false,
            null,
            false,
            null,
            false,
            null,
            false));

    assertThat(task.getTitle()).isEqualTo("기존");
    assertThat(task.getDescription()).isEqualTo("설명");
    assertThat(task.getStatus()).isEqualTo("done");
    assertThat(task.getPriority()).isEqualTo("high");
  }

  @Test
  void updateRejectsChangedAssigneeWhoIsNotWorkspaceMember() {
    UUID projectId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID assigneeId = UUID.randomUUID();
    Task task =
        Task.create(
            CreateTaskCommand.manual(projectId, workspaceId, "기존", "pm", "high"), "MOM-0004");
    when(taskRepository.findByIdAndDeletedAtIsNull(task.getId())).thenReturn(Optional.of(task));
    when(workspaceMembershipReader.roleOf(workspaceId, assigneeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                taskWriter.update(
                    new UpdateTaskCommand(
                        task.getId(), "새 제목", "pm", assigneeId, "high", "todo", null, List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }
}
