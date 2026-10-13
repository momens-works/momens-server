package works.momens.server.project.core.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 소유자 생성 시각은 DB DEFAULT로 채웁니다. 같은 트랜잭션의 행들이 같은 시각을 가져야 레거시와 같은 사용자 id 보조 정렬이 적용됩니다. */
@Getter
@Entity
@Table(name = "project_owners")
@IdClass(ProjectOwnerId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProjectOwner {

  @Id
  @Column(name = "project_id", columnDefinition = "uuid")
  private UUID projectId;

  @Id
  @Column(name = "owner_user_id", columnDefinition = "uuid")
  private UUID ownerUserId;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  static ProjectOwner of(UUID projectId, UUID ownerUserId) {
    ProjectOwner owner = new ProjectOwner();
    owner.projectId = projectId;
    owner.ownerUserId = ownerUserId;
    return owner;
  }
}
