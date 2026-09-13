package works.momens.server.project.task.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.milestone.MilestoneDirectory;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@Service
@RequiredArgsConstructor
class TaskWriterImpl implements TaskWriter {

  private static final String EVENT_TASK_CREATED = "task.created";

  private final TaskRepository taskRepository;
  private final MilestoneDirectory milestoneDirectory;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final LabelAllocator labelAllocator;
  private final OutboxAppender outboxAppender;

  @Override
  @Transactional
  public TaskSnapshot create(CreateTaskCommand command) {
    validateReferences(
        command.workspaceId(), command.projectId(), command.milestoneId(), command.assigneeId());
    String label = labelAllocator.allocateMomLabel(command.workspaceId());
    Task task = Task.create(command, label);
    taskRepository.save(task);
    appendCreatedEvent(task);
    return TaskSnapshotMapper.toTaskWorkspaceSnapshot(task);
  }

  @Override
  @Transactional
  public TaskDetail update(UpdateTaskCommand command) {
    Task task = findTask(command.taskId());
    // 모바일은 편집 상태 전체를 보내므로, 기존 담당자는 재검증하지 않고 새 담당자만 검증한다.
    if (!Objects.equals(task.getAssigneeId(), command.assigneeId())) {
      validateReferences(task.getWorkspaceId(), task.getProjectId(), null, command.assigneeId());
    }
    task.update(
        command.title(),
        command.role(),
        command.priority(),
        command.status(),
        command.purpose(),
        command.assigneeId());
    validateChecklistItemIds(task, command.checklistItems());
    task.replaceChecklist(command.checklistItems());
    return TaskDetailMapper.toDetail(task);
  }

  @Override
  @Transactional
  public TaskSnapshot patch(PatchTaskCommand command) {
    Task task = findTask(command.taskId());
    if (command.milestoneSet() || command.assigneeSet()) {
      validateReferences(
          task.getWorkspaceId(),
          task.getProjectId(),
          command.milestoneSet() ? command.milestoneId() : null,
          command.assigneeSet() ? command.assigneeId() : null);
    }
    task.patch(
        command.title(),
        command.titleSet(),
        command.description(),
        command.descriptionSet(),
        command.status(),
        command.statusSet(),
        command.priority(),
        command.prioritySet(),
        command.milestoneId(),
        command.milestoneSet(),
        command.assigneeId(),
        command.assigneeSet(),
        command.dueDate(),
        command.dueDateSet());
    return TaskSnapshotMapper.toTaskWorkspaceSnapshot(task);
  }

  @Override
  @Transactional
  public TaskDetail toggleChecklistItem(UUID taskId, UUID itemId, boolean completed) {
    Task task = findTask(taskId);
    TaskChecklistItem item =
        task.getChecklistItems().stream()
            .filter(candidate -> candidate.getId().equals(itemId))
            .findFirst()
            .orElseThrow(
                () ->
                    new BusinessException(
                        TaskErrorCode.TASK_CHECKLIST_ITEM_NOT_FOUND,
                        Map.of("checklist_item_id", itemId.toString())));
    item.changeCompleted(completed);
    return TaskDetailMapper.toDetail(task);
  }

  @Override
  @Transactional
  public void delete(UUID taskId) {
    findTask(taskId).delete();
  }

  private void validateReferences(
      UUID workspaceId, UUID projectId, UUID milestoneId, UUID assigneeId) {
    if (milestoneId != null && !milestoneDirectory.existsInProject(milestoneId, projectId)) {
      throw FieldValidationException.forField("milestone_id");
    }
    if (assigneeId != null && workspaceMembershipReader.roleOf(workspaceId, assigneeId).isEmpty()) {
      throw FieldValidationException.forField("assignee_id");
    }
  }

  private void validateChecklistItemIds(
      Task task, List<UpdateTaskCommand.ChecklistItemEdit> edits) {
    Set<UUID> existingIds =
        task.getChecklistItems().stream().map(TaskChecklistItem::getId).collect(Collectors.toSet());
    Set<UUID> seenIds = new HashSet<>();
    for (UpdateTaskCommand.ChecklistItemEdit edit : edits) {
      if (edit.id() == null) {
        continue;
      }
      if (!existingIds.contains(edit.id())) {
        throw new BusinessException(
            TaskErrorCode.TASK_CHECKLIST_ITEM_NOT_FOUND,
            Map.of("checklist_item_id", edit.id().toString()));
      }
      if (!seenIds.add(edit.id())) {
        throw new BusinessException(
            CommonErrorCode.COMMON_VALIDATION_FAILED,
            Map.of("checklist_item_id", edit.id().toString()));
      }
    }
  }

  private Task findTask(UUID taskId) {
    return taskRepository
        .findByIdAndDeletedAtIsNull(taskId)
        .orElseThrow(
            () ->
                new BusinessException(
                    TaskErrorCode.TASK_NOT_FOUND, Map.of("task_id", taskId.toString())));
  }

  private void appendCreatedEvent(Task task) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("origin_type", task.getOriginType());
    payload.put(
        "origin_signal_id",
        task.getOriginSignalId() == null ? null : task.getOriginSignalId().toString());
    outboxAppender.append(
        task.getWorkspaceId(), "task", task.getId().toString(), EVENT_TASK_CREATED, payload);
  }
}
