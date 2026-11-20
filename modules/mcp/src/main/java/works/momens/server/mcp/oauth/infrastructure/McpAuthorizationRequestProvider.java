package works.momens.server.mcp.oauth.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import works.momens.server.mcp.oauth.application.McpInteractionService;

/** SAS validates the request; Momens chooses a workspace on its existing consent surface. */
@RequiredArgsConstructor
final class McpAuthorizationRequestProvider implements AuthenticationProvider {
  private final McpInteractionService interactions;
  private final RegisteredClientRepository clients;
  private final String resource;

  @Override
  public Authentication authenticate(Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthorizationCodeRequestAuthenticationToken request)) {
      throw new OAuth2AuthenticationException("invalid_request");
    }
    RegisteredClient client = clients.findByClientId(request.getClientId());
    if (client == null) {
      throw new OAuth2AuthenticationException("invalid_request");
    }
    new McpAuthorizationRequestValidator(resource)
        .accept(
            OAuth2AuthorizationCodeRequestAuthenticationContext.with(request)
                .registeredClient(client)
                .build());
    request.setDetails(interactions.begin(request));
    return request;
  }

  @Override
  public boolean supports(Class<?> type) {
    return OAuth2AuthorizationCodeRequestAuthenticationToken.class.isAssignableFrom(type)
        || OAuth2AuthorizationConsentAuthenticationToken.class.isAssignableFrom(type);
  }
}
