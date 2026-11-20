package works.momens.server.project.taskupdate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TaskUpdateDetail(
    UUID id,
    UUID workspaceId,
    UUID projectId,
    UUID taskId,
    UUID authorId,
    String body,
    String kind,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {}
