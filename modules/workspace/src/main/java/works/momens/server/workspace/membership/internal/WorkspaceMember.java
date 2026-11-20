package works.momens.server.workspace.membership.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import works.momens.server.workspace.membership.WorkspaceRole;

/** 호출자가 복합 PK를 채우므로 {@link Persistable}로 새 엔티티를 판정해 불필요한 merge 조회를 피합니다. 감사 필드가 비어 있으면 새 엔티티입니다. */
@Getter
@Entity
@Table(name = "workspace_members")
@IdClass(WorkspaceMemberId.class)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WorkspaceMember implements Persistable<WorkspaceMemberId> {

  @Id
  @Column(name = "workspace_id", columnDefinition = "uuid")
  private UUID workspaceId;

  @Id
  @Column(name = "user_id", columnDefinition = "uuid")
  private UUID userId;

  @Column(nullable = false)
  private String role;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Builder
  private WorkspaceMember(UUID workspaceId, UUID userId, String role) {
    this.workspaceId = workspaceId;
    this.userId = userId;
    this.role = role;
  }

  void changeRole(WorkspaceRole role) {
    this.role = role.value();
  }

  @Override
  public WorkspaceMemberId getId() {
    return new WorkspaceMemberId(workspaceId, userId);
  }

  @Override
  public boolean isNew() {
    return createdAt == null;
  }
}
