package works.momens.server.web.task;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class TaskWriteService {
  private final TaskWriter taskWriter;
  private final TaskReader taskReader;
  private final ProjectReader projectReader;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  @Transactional
  TaskSnapshot create(
      UUID projectId,
      UUID userId,
      String title,
      String description,
      TaskStatus status,
      UUID milestoneId,
      TaskPriority priority,
      UUID assigneeId,
      LocalDate dueDate) {
    UUID workspaceId = requireProject(projectId);
    requireMember(workspaceId, userId);
    if (title == null || title.isBlank()) {
      throw FieldValidationException.forField("title");
    }
    return taskWriter.create(
        new CreateTaskCommand(
            projectId,
            workspaceId,
            title,
            description == null || description.isEmpty() ? null : description,
            status == null ? TaskStatus.BACKLOG : status,
            null,
            priority,
            milestoneId,
            assigneeId,
            dueDate,
            TaskOrigin.MANUAL,
            null));
  }

  @Transactional
  TaskSnapshot update(
      UUID taskId,
      UUID userId,
      String title,
      boolean titleSet,
      String description,
      boolean descriptionSet,
      TaskStatus status,
      boolean statusSet,
      TaskPriority priority,
      boolean prioritySet,
      UUID milestoneId,
      boolean milestoneSet,
      UUID assigneeId,
      boolean assigneeSet,
      LocalDate dueDate,
      boolean dueDateSet) {
    requireTaskMember(taskId, userId);
    if (titleSet && title == null) {
      throw FieldValidationException.forField("title");
    }
    if (statusSet && status == null) {
      throw FieldValidationException.forField("status");
    }
    if (prioritySet && priority == null) {
      throw FieldValidationException.forField("priority");
    }
    boolean effectiveTitleSet = titleSet && !title.isEmpty();
    return taskWriter.patch(
        new PatchTaskCommand(
            taskId,
            title,
            effectiveTitleSet,
            description,
            descriptionSet,
            status,
            statusSet,
            priority,
            prioritySet,
            milestoneId,
            milestoneSet,
            assigneeId,
            assigneeSet,
            dueDate,
            dueDateSet));
  }

  @Transactional
  void delete(UUID taskId, UUID userId) {
    requireTaskMember(taskId, userId);
    taskWriter.delete(taskId);
  }

  private UUID requireProject(UUID projectId) {
    return projectReader
        .workspaceIdOf(projectId)
        .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
  }

  private void requireTaskMember(UUID taskId, UUID userId) {
    UUID workspaceId =
        taskReader
            .findScope(taskId)
            .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND))
            .workspaceId();
    requireMember(workspaceId, userId);
  }

  private void requireMember(UUID workspaceId, UUID userId) {
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
  }
}
