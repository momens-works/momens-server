package works.momens.server.web.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberView(
    UUID userId, String email, String name, String role, Instant createdAt, Instant updatedAt) {}
