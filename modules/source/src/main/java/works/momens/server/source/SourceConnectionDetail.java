package works.momens.server.source;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SourceConnectionDetail(
    UUID id,
    UUID workspaceId,
    String sourceType,
    String status,
    String externalWorkspaceId,
    String externalWorkspaceName,
    UUID connectedByUserId,
    Instant connectedAt,
    Instant lastSyncedAt,
    Instant disabledAt,
    Instant resyncRequestedAt,
    long capturesReadCount,
    long candidatesExtractedCount,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {}
