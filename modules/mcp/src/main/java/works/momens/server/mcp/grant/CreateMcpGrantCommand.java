package works.momens.server.mcp.grant;

import java.util.Collection;
import java.util.UUID;

/** Command for approving one user's access to one workspace for one MCP client. */
public record CreateMcpGrantCommand(
    UUID userId, String clientId, UUID workspaceId, Collection<String> scopes) {}
