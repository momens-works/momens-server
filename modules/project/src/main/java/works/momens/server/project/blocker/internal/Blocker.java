package works.momens.server.project.blocker.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.blocker.BlockedEntityType;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerStatus;

@Getter
@Entity
@Table(name = "blockers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Blocker extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(nullable = false)
  private String description;

  @Column(nullable = false)
  private String status;

  @Column(name = "blocked_entity_type", nullable = false)
  private String blockedEntityType;

  @Column(name = "blocked_entity_id", nullable = false, columnDefinition = "uuid")
  private UUID blockedEntityId;

  @Column(name = "task_id", columnDefinition = "uuid")
  private UUID taskId;

  @Column(name = "milestone_id", columnDefinition = "uuid")
  private UUID milestoneId;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  static Blocker createForTask(UUID workspaceId, UUID taskId, String description) {
    Blocker blocker = new Blocker();
    blocker.workspaceId = workspaceId;
    blocker.description = description;
    blocker.status = BlockerStatus.ACTIVE.value();
    blocker.blockedEntityType = BlockedEntityType.TASK.value();
    blocker.blockedEntityId = taskId;
    blocker.taskId = taskId;
    return blocker;
  }

  BlockerDetail toDetail() {
    return new BlockerDetail(
        getId(),
        workspaceId,
        description,
        status,
        blockedEntityType,
        blockedEntityId,
        getCreatedAt(),
        getUpdatedAt(),
        resolvedAt);
  }
}
