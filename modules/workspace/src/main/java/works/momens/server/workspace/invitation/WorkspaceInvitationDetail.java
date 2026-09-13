package works.momens.server.workspace.invitation;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceInvitationDetail(
    UUID id,
    UUID workspaceId,
    String email,
    String role,
    UUID inviterId,
    InvitationStatus status,
    Instant expiresAt,
    Instant acceptedAt,
    Instant revokedAt,
    Instant lastSentAt,
    Instant createdAt,
    Instant updatedAt) {}
