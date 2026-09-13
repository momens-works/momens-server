package works.momens.server.workspace.invitation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.workspace.invitation.InvitationStatus;

/**
 * 워크스페이스 초대를 나타냅니다.
 *
 * <p>레거시 {@code momens-api}의 {@code workspace_invitations} 테이블과 호환됩니다. 식별자와 감사 필드는 {@link
 * BaseEntity}에서 관리합니다.
 *
 * <p>초대 생성 경로에서는 이 엔티티를 직접 사용하지 않습니다. 대기 중인 초대가 있으면 기존 행을 덮어쓰는 단일 쿼리가 필요하지만 이를 JPA로 표현하기 어려워 해당 쿼리만
 * 별도로 처리합니다. 재발송, 폐기, 수락은 조회한 엔티티의 상태를 변경하는 방식으로 반영합니다.
 */
@Getter
@Entity
@Table(name = "workspace_invitations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WorkspaceInvitation extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(nullable = false)
  private String email;

  @Column(nullable = false)
  private String role;

  @Column(name = "inviter_id", columnDefinition = "uuid")
  private UUID inviterId;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(nullable = false)
  private String status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "last_sent_at")
  private Instant lastSentAt;

  void markSent(Instant sentAt) {
    this.lastSentAt = sentAt;
  }

  void markAccepted(Instant acceptedAt) {
    this.status = InvitationStatus.ACCEPTED.value();
    this.acceptedAt = acceptedAt;
  }

  InvitationStatus effectiveStatus(Instant now) {
    InvitationStatus stored = InvitationStatus.from(status).orElseThrow();
    if (stored == InvitationStatus.PENDING && expiresAt.isBefore(now)) {
      return InvitationStatus.EXPIRED;
    }
    return stored;
  }
}
