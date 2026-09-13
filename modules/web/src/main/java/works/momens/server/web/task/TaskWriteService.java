package works.momens.server.web.task;

import java.time.LocalDate;
import java.util.Locale;
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
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
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
      String status,
      UUID milestoneId,
      String priority,
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
            normalizeStatus(status, true),
            null,
            normalizePriority(priority, true),
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
      String status,
      boolean statusSet,
      String priority,
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
    boolean effectiveStatusSet = statusSet && !status.isEmpty();
    boolean effectivePrioritySet = prioritySet && !priority.isEmpty();
    return taskWriter.patch(
        new PatchTaskCommand(
            taskId,
            title,
            effectiveTitleSet,
            description,
            descriptionSet,
            effectiveStatusSet ? normalizeStatus(status, false) : status,
            effectiveStatusSet,
            effectivePrioritySet ? normalizePriority(priority, false) : priority,
            effectivePrioritySet,
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

  private static String normalizeStatus(String value, boolean allowDefault) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() && allowDefault) {
      return "backlog";
    }
    return switch (normalized) {
      case "backlog", "todo", "done", "cancelled" -> normalized;
      case "in_progress", "progress", "in-progress" -> "in_progress";
      default -> throw FieldValidationException.forField("status");
    };
  }

  private static String normalizePriority(String value, boolean allowDefault) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() && allowDefault) {
      return "medium";
    }
    return switch (normalized) {
      case "low", "high", "urgent" -> normalized;
      case "medium", "med" -> "medium";
      default -> throw FieldValidationException.forField("priority");
    };
  }
}
