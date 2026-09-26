package works.momens.server.mcp.grant.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface McpGrantRepository extends JpaRepository<McpGrant, UUID> {

  Optional<McpGrant> findByIdAndRevokedAtIsNull(UUID id);

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
