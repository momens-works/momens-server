package works.momens.server.workspace.core;

import java.time.Instant;
import java.util.UUID;

/** {@code description}이 없으면 null이며, 응답에서 생략할지는 호출자의 DTO가 결정합니다. */
public record WorkspaceDetail(
    UUID id, String name, String slug, String description, Instant createdAt, Instant updatedAt) {}
