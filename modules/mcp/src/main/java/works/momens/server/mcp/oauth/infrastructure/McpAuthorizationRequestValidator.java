package works.momens.server.mcp.oauth.infrastructure;

import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

@RequiredArgsConstructor
final class McpAuthorizationRequestValidator
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
  private final String resource;

  @Override
  public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
    if (request.getRedirectUri() == null
        || !context.getRegisteredClient().getRedirectUris().contains(request.getRedirectUri())
        || !context
            .getRegisteredClient()
            .getClientAuthenticationMethods()
            .equals(Set.of(ClientAuthenticationMethod.NONE))
        || !context
            .getRegisteredClient()
            .getAuthorizationGrantTypes()
            .contains(AuthorizationGrantType.AUTHORIZATION_CODE)
        || !"S256".equals(request.getAdditionalParameters().get("code_challenge_method"))
        || !(request.getAdditionalParameters().get("code_challenge") instanceof String challenge)
        || !challenge.matches("[A-Za-z0-9_-]{43}")
        || request.getAdditionalParameters().containsKey("request_uri")) {
      throw new OAuth2AuthenticationException("invalid_request");
    }
    if (!resource.equals(request.getAdditionalParameters().get("resource"))) {
      throw new OAuth2AuthenticationException("invalid_target");
    }
    OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
  }
}
