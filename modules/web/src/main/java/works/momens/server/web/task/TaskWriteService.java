package works.momens.server.web.task;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  // 레거시 웹에서 사용하던 입력 별칭만 이 모듈에서 관리합니다. 정규 값인지 여부는 도메인 enum으로 판정합니다.
  private static final Map<String, TaskStatus> STATUS_ALIASES =
      Map.of("progress", TaskStatus.IN_PROGRESS, "in-progress", TaskStatus.IN_PROGRESS);
  private static final Map<String, TaskPriority> PRIORITY_ALIASES =
      Map.of("med", TaskPriority.MEDIUM);

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
            normalizePriority(priority),
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
            effectiveStatusSet ? normalizeStatus(status, false) : null,
            effectiveStatusSet,
            effectivePrioritySet ? normalizePriority(priority) : null,
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

  private static TaskStatus normalizeStatus(String value, boolean allowDefault) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() && allowDefault) {
      return TaskStatus.BACKLOG;
    }
    return TaskStatus.from(normalized)
        .or(() -> Optional.ofNullable(STATUS_ALIASES.get(normalized)))
        .orElseThrow(() -> FieldValidationException.forField("status"));
  }

  private static TaskPriority normalizePriority(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    return TaskPriority.from(normalized)
        .or(() -> Optional.ofNullable(PRIORITY_ALIASES.get(normalized)))
        .orElseThrow(() -> FieldValidationException.forField("priority"));
  }
}
