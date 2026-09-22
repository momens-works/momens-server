package works.momens.server.mcp.grant;

import java.util.Optional;
import java.util.UUID;

/** Public read API for active MCP grants. */
public interface McpGrantReader {

  Optional<McpGrantDetail> findActive(UUID grantId);
}
