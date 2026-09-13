package works.momens.server.workspace.membership;

import java.util.UUID;

public record AddMembershipCommand(UUID workspaceId, UUID userId, WorkspaceRole role) {}
