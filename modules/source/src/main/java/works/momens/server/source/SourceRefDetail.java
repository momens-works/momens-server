package works.momens.server.source;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SourceRefDetail(
    UUID id,
    UUID workspaceId,
    String sourceType,
    UUID sourceConnectionId,
    String sourceObjectType,
    String sourceObjectId,
    String sourceUrl,
    String title,
    String text,
    String snippet,
    String authorName,
    String authorEmail,
    Instant sourceCreatedAt,
    Instant sourceUpdatedAt,
    String visibility,
    String permissionKey,
    UUID verifiedByUserId,
    Instant verifiedAt,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {}
