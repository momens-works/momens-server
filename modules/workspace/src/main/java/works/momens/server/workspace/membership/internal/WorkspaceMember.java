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

/**
 * 워크스페이스 멤버십.
 *
 * <p>레거시 {@code momens-api}의 {@code workspace_members} 테이블과 호환됩니다. {@code (workspace_id, user_id)}
 * 복합 PK라 단일 UUID PK용 {@link works.momens.server.common.persistence.BaseEntity}를 상속하지 않고, 식별자는
 * {@link WorkspaceMemberId}로 두고 감사 필드를 직접 선언합니다.
 *
 * <p>{@code role}은 base persistence 단계라 문자열로만 둡니다. RBAC 규칙과 role 모델링은 RBAC public API(MOM-31)에서
 * 담당합니다. DB CHECK 제약이 {@code owner}/{@code admin}/{@code member}만 허용합니다.
 *
 * <p>복합 PK는 caller가 두 값을 모두 채우므로 Spring Data가 {@code save()}를 merge(불필요한 SELECT 후 INSERT)로 처리합니다.
 * {@link Persistable}을 구현해 감사 필드가 비어 있으면 새 엔티티로 판단하게 합니다.
 */
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

  /** 역할을 변경합니다. DB에는 문자열로 저장하므로 enum이 정의한 저장 값으로 변환해 반영합니다. */
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
