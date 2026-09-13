package works.momens.server.workspace.invitation;

import java.util.List;
import java.util.UUID;

public interface WorkspaceInvitationReader {

  List<WorkspaceInvitationDetail> listByWorkspaceId(UUID workspaceId);
}
