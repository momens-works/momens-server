package works.momens.server.mcp.oauth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Public refresh/revoke authentication; code exchange still uses SAS's PKCE provider. */
@RequiredArgsConstructor
final class McpPublicClientAuthentication
    implements AuthenticationConverter, AuthenticationProvider {
  private static final String PUBLIC_TOKEN_REQUEST = "mcp.public.token.request";
  private final RegisteredClientRepository clients;

  @Override
  public Authentication convert(HttpServletRequest request) {
    boolean refresh =
        McpAuthorizationServerConfig.TOKEN_ENDPOINT.equals(request.getRequestURI())
            && "refresh_token".equals(request.getParameter("grant_type"));
    boolean revoke =
        McpAuthorizationServerConfig.TOKEN_REVOCATION_ENDPOINT.equals(request.getRequestURI());
    if (!refresh && !revoke) {
      return null;
    }
    for (String parameter : request.getParameterMap().keySet()) {
      if (request.getParameterValues(parameter).length != 1) {
        throw new OAuth2AuthenticationException("invalid_request");
      }
    }
    String clientId = request.getParameter("client_id");
    if (clientId == null
        || clientId.isBlank()
        || request.getParameter("client_secret") != null
        || request.getHeader("Authorization") != null) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
    return new OAuth2ClientAuthenticationToken(
        clientId, ClientAuthenticationMethod.NONE, null, Map.of(PUBLIC_TOKEN_REQUEST, true));
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
    if (!Boolean.TRUE.equals(token.getAdditionalParameters().get(PUBLIC_TOKEN_REQUEST))) {
      return null;
    }
    RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
    if (client == null
        || !client
            .getClientAuthenticationMethods()
            .equals(Set.of(ClientAuthenticationMethod.NONE))) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
    return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
  }

  @Override
  public boolean supports(Class<?> type) {
    return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type);
  }
}
