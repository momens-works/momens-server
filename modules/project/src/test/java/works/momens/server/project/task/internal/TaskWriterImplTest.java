package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
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
            CreateTaskCommand.manual(
                projectId, workspaceId, "권한 요청 점검", TaskRole.BACKEND, TaskPriority.HIGH));

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
            CreateTaskCommand.fromSignal(
                projectId, workspaceId, "제목", TaskRole.PM, null, signalId));

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
                TaskStatus.BACKLOG,
                null,
                TaskPriority.MEDIUM,
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
            CreateTaskCommand.manual(projectId, workspaceId, "기존", TaskRole.PM, TaskPriority.HIGH),
            "MOM-0003");
    when(taskRepository.findForMutation(task.getId())).thenReturn(Optional.of(task));

    taskWriter.patch(
        new PatchTaskCommand(
            task.getId(),
            "새 제목",
            false,
            "설명",
            true,
            TaskStatus.DONE,
            true,
            TaskPriority.LOW,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null));

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
            CreateTaskCommand.manual(projectId, workspaceId, "기존", TaskRole.PM, TaskPriority.HIGH),
            "MOM-0004");
    when(taskRepository.findForMutation(task.getId())).thenReturn(Optional.of(task));
    when(workspaceMembershipReader.roleOf(workspaceId, assigneeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                taskWriter.update(
                    new UpdateTaskCommand(
                        task.getId(),
                        "새 제목",
                        TaskRole.PM,
                        assigneeId,
                        TaskPriority.HIGH,
                        TaskStatus.TODO,
                        null,
                        List.of(),
                        null)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  @DisplayName("서로 다른 수정은 각각 발행하고 동일 상태 재요청은 건너뛴다")
  void updateEmitsEachChangeAndSkipsRetryWithSameState() {
    Task task = newTask();
    when(taskRepository.findForMutation(task.getId())).thenReturn(Optional.of(task));

    taskWriter.update(updateCommand(task.getId(), "두 번째"));
    taskWriter.update(updateCommand(task.getId(), "세 번째"));
    taskWriter.update(updateCommand(task.getId(), "세 번째"));

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(outboxAppender, times(2))
        .appendWithIdempotencyKey(
            eq(task.getWorkspaceId()),
            eq("task"),
            eq(task.getId().toString()),
            eq("task.updated"),
            eq(Map.of()),
            keys.capture());
    assertThat(keys.getAllValues()).hasSize(2).doesNotHaveDuplicates();
    assertThat(keys.getAllValues())
        .allMatch(key -> key.startsWith("task.updated:" + task.getId() + ":"));
  }

  @Test
  @DisplayName("마일스톤·마감일 변경은 발행하고 값이 같으면 발행하지 않는다")
  void patchEmitsForMilestoneAndDueDateButNotForNoOp() {
    Task task = newTask();
    UUID milestoneId = UUID.randomUUID();
    when(taskRepository.findForMutation(task.getId())).thenReturn(Optional.of(task));
    when(milestoneDirectory.existsInProject(milestoneId, task.getProjectId())).thenReturn(true);

    taskWriter.patch(
        new PatchTaskCommand(
            task.getId(),
            "첫 번째",
            true,
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null));
    verify(outboxAppender, never())
        .appendWithIdempotencyKey(
            eq(task.getWorkspaceId()),
            eq("task"),
            eq(task.getId().toString()),
            eq("task.updated"),
            eq(Map.of()),
            anyString());

    taskWriter.patch(
        new PatchTaskCommand(
            task.getId(),
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            milestoneId,
            true,
            null,
            false,
            null,
            false,
            null));
    taskWriter.patch(
        new PatchTaskCommand(
            task.getId(),
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            LocalDate.of(2026, 10, 3),
            true,
            null));
    verify(outboxAppender, times(2))
        .appendWithIdempotencyKey(
            eq(task.getWorkspaceId()),
            eq("task"),
            eq(task.getId().toString()),
            eq("task.updated"),
            eq(Map.of()),
            anyString());
  }

  @Test
  @DisplayName("역할만 바뀌어도 수정 이벤트를 발행한다")
  void updateEmitsWhenOnlyRoleChanges() {
    Task task = newTask();
    when(taskRepository.findForMutation(task.getId())).thenReturn(Optional.of(task));

    taskWriter.update(
        new UpdateTaskCommand(
            task.getId(),
            "첫 번째",
            TaskRole.BACKEND,
            null,
            TaskPriority.HIGH,
            TaskStatus.TODO,
            null,
            List.of(),
            null));

    verify(outboxAppender)
        .appendWithIdempotencyKey(
            eq(task.getWorkspaceId()),
            eq("task"),
            eq(task.getId().toString()),
            eq("task.updated"),
            eq(Map.of()),
            anyString());
  }

  @Test
  @DisplayName("삭제 이벤트를 한 번 발행하고 재삭제는 발행하지 않는다")
  void deleteEmitsOnceAndRetryCannotEmit() {
    Task task = newTask();
    when(taskRepository.findForMutation(task.getId()))
        .thenReturn(Optional.of(task), Optional.empty());

    taskWriter.delete(task.getId(), null);
    assertThatThrownBy(() -> taskWriter.delete(task.getId(), null))
        .isInstanceOf(BusinessException.class);

    verify(outboxAppender)
        .append(task.getWorkspaceId(), "task", task.getId().toString(), "task.deleted", Map.of());
  }

  private Task newTask() {
    return Task.create(
        CreateTaskCommand.manual(
            UUID.randomUUID(), UUID.randomUUID(), "첫 번째", TaskRole.PM, TaskPriority.HIGH),
        "MOM-0005");
  }

  private UpdateTaskCommand updateCommand(UUID taskId, String title) {
    return new UpdateTaskCommand(
        taskId,
        title,
        TaskRole.PM,
        null,
        TaskPriority.HIGH,
        TaskStatus.TODO,
        null,
        List.of(),
        null);
  }
}
