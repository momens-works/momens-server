package works.momens.server.mcp.grant;

import java.time.Instant;
import java.util.UUID;

/** Public write API for MCP grants. */
public interface McpGrantWriter {

  McpGrantDetail create(CreateMcpGrantCommand command);

  void revoke(UUID grantId, Instant revokedAt);
}
