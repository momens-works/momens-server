package works.momens.server.web.workspace;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.mcp.McpClientReader;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.web.workspace.dto.response.McpGrantsResponse;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class WorkspaceMcpGrantService {
  private final WorkspaceAccessChecker access;
  private final McpGrantReader grants;
  private final McpGrantWriter writer;
  private final McpClientReader clients;

  @Transactional(readOnly = true)
  public List<McpGrantsResponse.Grant> list(UUID workspaceId, UUID userId) {
    access.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return grants.findActiveConnections(workspaceId, userId).stream()
        .flatMap(grant ->
            clients
                .findClientName(grant.clientId())
                .map(name -> McpGrantsResponse.Grant.from(grant, name))
                .stream())
        .toList();
  }

  @Transactional
  public void revoke(UUID workspaceId, UUID userId, UUID grantId) {
    access.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    var grant = grants.findActive(grantId).orElseThrow(AccessDenied::new);
    if (!workspaceId.equals(grant.workspaceId()) || !userId.equals(grant.userId())) {
      throw new AccessDenied();
    }
    writer.revoke(grantId, null);
  }

  /** Missing, revoked and other users' grants share the legacy forbidden response. */
  static class AccessDenied extends RuntimeException {}
}
