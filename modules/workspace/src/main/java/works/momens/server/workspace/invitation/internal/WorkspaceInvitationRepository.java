package works.momens.server.workspace.invitation.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

  List<WorkspaceInvitation> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

  Optional<WorkspaceInvitation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

  /**
   * 초대 수락은 초대 조회부터 멤버 등록까지 하나의 트랜잭션으로 처리합니다.
   *
   * <p>같은 토큰으로 동시에 들어온 요청이 모두 검증을 통과하지 않도록 초대 행에 lock을 건 상태로 조회합니다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE workspace_invitations
          SET token_hash = :tokenHash, status = 'pending', revoked_at = NULL,
              expires_at = :expiresAt, updated_at = :now
          WHERE id = :id AND status <> 'accepted'
          """,
      nativeQuery = true)
  int rotateToken(
      @Param("id") UUID id,
      @Param("tokenHash") String tokenHash,
      @Param("expiresAt") Instant expiresAt,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE workspace_invitations
          SET status = 'revoked', revoked_at = :now, updated_at = :now
          WHERE id = :id AND status <> 'accepted'
          """,
      nativeQuery = true)
  int revoke(@Param("id") UUID id, @Param("now") Instant now);
}
