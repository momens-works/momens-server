package works.momens.server.project.task.internal;

import java.util.UUID;
import works.momens.server.project.task.TaskSnapshot;

final class TaskSnapshotMapper {

  private TaskSnapshotMapper() {}

  static TaskSnapshot toTaskWorkspaceSnapshot(Task task) {
    return toSnapshot(task, task.getWorkspaceId());
  }

  static TaskSnapshot toProjectWorkspaceSnapshot(Task task, UUID workspaceId) {
    return toSnapshot(task, workspaceId);
  }

  private static TaskSnapshot toSnapshot(Task task, UUID workspaceId) {
    return new TaskSnapshot(
        task.getId(),
        workspaceId,
        task.getProjectId(),
        task.getMilestoneId(),
        task.getLabel(),
        task.getTitle(),
        task.getDescription(),
        task.getStatus(),
        task.getPriority(),
        task.getRole(),
        task.getAssigneeId(),
        task.getDueDate(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }
}
