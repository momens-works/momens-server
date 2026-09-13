package works.momens.server.workspace.invitation;

import java.util.UUID;

public record RevokeInvitationCommand(UUID workspaceId, UUID invitationId) {}
