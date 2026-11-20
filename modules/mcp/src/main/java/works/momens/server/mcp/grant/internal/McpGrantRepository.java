package works.momens.server.mcp.grant.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface McpGrantRepository extends JpaRepository<McpGrant, UUID> {

  Optional<McpGrant> findByIdAndRevokedAtIsNull(UUID id);

  List<McpGrant> findByWorkspaceIdAndUserIdAndRevokedAtIsNullOrderByCreatedAtDescIdAsc(
      UUID workspaceId, UUID userId);

  @Modifying(flushAutomatically = true)
  @Query(
      """
      UPDATE McpGrant g
      SET g.lastUsedAt = CASE WHEN g.lastUsedAt IS NULL OR g.lastUsedAt < :usedAt
          THEN :usedAt ELSE g.lastUsedAt END,
          g.updatedAt = CASE WHEN g.updatedAt < :usedAt THEN :usedAt ELSE g.updatedAt END
      WHERE g.id = :grantId AND g.revokedAt IS NULL
      """)
  int recordUsage(@Param("grantId") UUID grantId, @Param("usedAt") Instant usedAt);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE McpGrant g SET g.revokedAt = :revokedAt, g.updatedAt = :revokedAt
      WHERE g.id = :grantId AND g.revokedAt IS NULL
      """)
  int revokeActive(@Param("grantId") UUID grantId, @Param("revokedAt") Instant revokedAt);

  Optional<McpGrant> findByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
      UUID userId, String clientId, UUID workspaceId);

  // A row lock cannot serialize first approvals: no grant row exists yet.
  @Query(
      value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:subject, 0))",
      nativeQuery = true)
  int lockSubject(@Param("subject") String subject);

  boolean existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
      UUID userId, String clientId, UUID workspaceId);
}
