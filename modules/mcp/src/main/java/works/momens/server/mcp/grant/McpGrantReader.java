package works.momens.server.mcp.grant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpGrantReader {

  Optional<McpGrantDetail> findActive(UUID grantId);

  /** Active connections owned by this user in this workspace, newest first. */
  List<McpGrantConnection> findActiveConnections(UUID workspaceId, UUID userId);
}
