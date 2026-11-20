package works.momens.server.project.milestone.internal;

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

/** 생성 시각은 같은 트랜잭션의 정렬 기준을 맞추도록 DB DEFAULT로 채웁니다. 소유자 행이 없으면 프로젝트와 달리 폴백하지 않습니다. */
@Getter
@Entity
@Table(name = "milestone_owners")
@IdClass(MilestoneOwnerId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MilestoneOwner {

  @Id
  @Column(name = "milestone_id", columnDefinition = "uuid")
  private UUID milestoneId;

  @Id
  @Column(name = "owner_user_id", columnDefinition = "uuid")
  private UUID ownerUserId;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  static MilestoneOwner of(UUID milestoneId, UUID ownerUserId) {
    MilestoneOwner owner = new MilestoneOwner();
    owner.milestoneId = milestoneId;
    owner.ownerUserId = ownerUserId;
    return owner;
  }
}
