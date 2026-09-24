package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class McpTransportAuthenticator {

  private final McpBearerTokenVerifier bearerTokenVerifier;

  Optional<McpAuthenticationContext> authenticate(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null
        || authorization.length() <= "Bearer ".length()
        || !authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      return Optional.empty();
    }
    String token = authorization.substring("Bearer ".length()).trim();
    return token.isEmpty() ? Optional.empty() : bearerTokenVerifier.verify(token);
  }
}
