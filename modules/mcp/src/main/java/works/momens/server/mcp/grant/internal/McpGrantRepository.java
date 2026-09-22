package works.momens.server.mcp.grant.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface McpGrantRepository extends JpaRepository<McpGrant, UUID> {

  Optional<McpGrant> findByIdAndRevokedAtIsNull(UUID id);

  boolean existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
      UUID userId, String clientId, UUID workspaceId);
}
