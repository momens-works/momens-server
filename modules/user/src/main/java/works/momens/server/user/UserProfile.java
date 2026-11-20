package works.momens.server.user;

import java.time.Instant;
import java.util.UUID;

public record UserProfile(
    UUID id,
    String email,
    String name,
    String jobRole,
    String avatarUrl,
    Instant createdAt,
    Instant updatedAt) {}
