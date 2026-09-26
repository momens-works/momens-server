package works.momens.server.mcp.oauth.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import works.momens.server.mcp.oauth.application.McpTokenService;

/** Adapts SAS authentication providers to the transactional application service. */
@RequiredArgsConstructor
final class McpTokenAuthenticationProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final McpTokenService tokens;

  @Override
  public Authentication authenticate(Authentication authentication) {
    return tokens.authenticate(authentication, delegate);
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }
}
