package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;

@Component
class McpTransportAuthenticator {

  private final McpBearerTokenVerifier bearerTokenVerifier;

  McpTransportAuthenticator(McpBearerTokenVerifier bearerTokenVerifier) {
    this.bearerTokenVerifier = bearerTokenVerifier;
  }

  Optional<McpAuthenticationContext> authenticate(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return Optional.empty();
    }
    String token = authorization.substring("Bearer ".length()).trim();
    return token.isEmpty() ? Optional.empty() : bearerTokenVerifier.verify(token);
  }
}
