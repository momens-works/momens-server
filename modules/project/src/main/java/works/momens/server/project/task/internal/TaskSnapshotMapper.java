package works.momens.server.project.task.internal;

import works.momens.server.project.task.TaskSnapshot;

final class TaskSnapshotMapper {

  private TaskSnapshotMapper() {}

  static TaskSnapshot toSnapshot(Task task) {
    return new TaskSnapshot(
        task.getId(),
        task.getWorkspaceId(),
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
        task.getUpdatedAt(),
        task.getVersion());
  }
}
