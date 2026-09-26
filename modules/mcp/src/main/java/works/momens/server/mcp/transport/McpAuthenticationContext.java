package works.momens.server.mcp.transport;

import java.util.Set;
import java.util.UUID;
import works.momens.server.mcp.grant.McpScope;

/** OAuth verifier가 MCP 요청에 전달하는 최소 인증 문맥입니다. */
public record McpAuthenticationContext(
    UUID grantId, UUID userId, String clientId, UUID workspaceId, Set<String> scopes) {
  public McpAuthenticationContext {
    scopes = Set.copyOf(scopes);
  }

  public boolean permits(UUID resourceWorkspaceId, McpScope scope) {
    return workspaceId.equals(resourceWorkspaceId) && scopes.contains(scope.value());
  }
}
