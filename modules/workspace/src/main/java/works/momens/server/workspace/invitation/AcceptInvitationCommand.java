package works.momens.server.workspace.invitation;

import java.util.UUID;

public record AcceptInvitationCommand(UUID userId, String rawToken) {}
