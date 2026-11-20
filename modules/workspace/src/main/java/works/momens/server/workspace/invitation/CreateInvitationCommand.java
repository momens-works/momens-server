package works.momens.server.workspace.invitation;

import java.util.UUID;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;

public record CreateInvitationCommand(
    UUID workspaceId, UUID inviterUserId, String email, AssignableWorkspaceRole role) {}
