package works.momens.server.web.source;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import works.momens.server.source.SourceConnectionReader;
import works.momens.server.source.SourceConnectionWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class SourceConnectionCommandService {

  private final SourceConnectionReader sourceConnectionReader;
  private final SourceConnectionWriter sourceConnectionWriter;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  public void requestResync(UUID connectionId, UUID userId) {
    UUID workspaceId = sourceConnectionReader.getWorkspaceId(connectionId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    sourceConnectionWriter.requestResync(connectionId, workspaceId);
  }
}
