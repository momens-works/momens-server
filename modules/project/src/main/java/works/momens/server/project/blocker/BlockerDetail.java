package works.momens.server.project.blocker;

import java.time.Instant;
import java.util.UUID;

public record BlockerDetail(
    UUID id,
    UUID workspaceId,
    String description,
    String status,
    String blockedEntityType,
    UUID blockedEntityId,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt) {}
