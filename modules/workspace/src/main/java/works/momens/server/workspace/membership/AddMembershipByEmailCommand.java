package works.momens.server.workspace.membership;

import java.util.UUID;

public record AddMembershipByEmailCommand(UUID workspaceId, String email, WorkspaceRole role) {}
