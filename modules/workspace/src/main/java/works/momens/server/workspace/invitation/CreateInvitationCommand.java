package works.momens.server.workspace.invitation;

import java.util.UUID;
import works.momens.server.workspace.membership.WorkspaceRole;

public record CreateInvitationCommand(
    UUID workspaceId, UUID inviterUserId, String email, WorkspaceRole role) {}
