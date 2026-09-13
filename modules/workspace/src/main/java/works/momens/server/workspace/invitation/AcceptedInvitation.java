package works.momens.server.workspace.invitation;

import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;

public record AcceptedInvitation(WorkspaceDetail workspace, WorkspaceMembershipDetail membership) {}
