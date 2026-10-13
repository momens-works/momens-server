package works.momens.server.mcp.grant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record McpGrantDetail(
    UUID id,
    UUID userId,
    String clientId,
    UUID workspaceId,
    List<String> scopes,
    Instant approvedAt,
    Instant revokedAt,
    Instant createdAt) {}
