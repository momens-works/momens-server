package works.momens.server.mcp.grant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model for the MCP permission aggregate. */
public record McpGrantDetail(
    UUID id,
    UUID userId,
    String clientId,
    UUID workspaceId,
    List<String> scopes,
    Instant approvedAt,
    Instant revokedAt,
    Instant createdAt) {}
