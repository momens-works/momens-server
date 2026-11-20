package works.momens.server.mcp.grant;

import java.util.Collection;
import java.util.UUID;

public record CreateMcpGrantCommand(
    UUID userId, String clientId, UUID workspaceId, Collection<String> scopes) {}
