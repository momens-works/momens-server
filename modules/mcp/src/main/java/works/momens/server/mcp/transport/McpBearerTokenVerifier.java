package works.momens.server.mcp.transport;

import java.util.Optional;

/** MCP bearer token을 검증하고 tool 호출에 전달할 인증 문맥을 만드는 port입니다. */
public interface McpBearerTokenVerifier {
  Optional<McpAuthenticationContext> verify(String bearerToken);
}
