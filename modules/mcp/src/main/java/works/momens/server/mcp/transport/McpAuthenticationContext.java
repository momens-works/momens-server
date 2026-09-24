package works.momens.server.mcp.transport;

import java.util.Set;
import java.util.UUID;

/** OAuth verifier가 MCP 요청에 전달하는 최소 인증 문맥입니다. */
public record McpAuthenticationContext(UUID grantId, String subject, Set<String> scopes) {
  public McpAuthenticationContext {
    scopes = Set.copyOf(scopes);
  }
}
