package works.momens.server.project.taskupdate.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateKind;

/** 레거시 task_updates 매핑입니다. */
@Getter
@Entity
@Table(name = "task_updates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TaskUpdate extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
  private UUID projectId;

  @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
  private UUID taskId;

  @Column(name = "author_id", columnDefinition = "uuid")
  private UUID authorId;

  @Column(nullable = false)
  private String body;

  @Column(nullable = false)
  private String kind;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  private TaskUpdate(
      UUID workspaceId,
      UUID projectId,
      UUID taskId,
      UUID authorId,
      String body,
      TaskUpdateKind kind,
      Map<String, Object> metadata) {
    this.workspaceId = workspaceId;
    this.projectId = projectId;
    this.taskId = taskId;
    this.authorId = authorId;
    this.body = body;
    this.kind = (kind != null ? kind : TaskUpdateKind.COMMENT).value();
    this.metadata = metadata;
  }

  static TaskUpdate create(
      UUID workspaceId,
      UUID projectId,
      UUID taskId,
      UUID authorId,
      String body,
      TaskUpdateKind kind,
      Map<String, Object> metadata) {
    return new TaskUpdate(workspaceId, projectId, taskId, authorId, body, kind, metadata);
  }

  void delete() {
    this.deletedAt = Instant.now();
  }

  TaskUpdateDetail toDetail() {
    return new TaskUpdateDetail(
        getId(),
        workspaceId,
        projectId,
        taskId,
        authorId,
        body,
        kind,
        metadata,
        getCreatedAt(),
        getUpdatedAt());
  }
}
