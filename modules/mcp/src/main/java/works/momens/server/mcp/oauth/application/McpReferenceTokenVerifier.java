package works.momens.server.mcp.oauth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.mcp.configuration.McpEndpointProperties;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** Resolves reference tokens against the current OAuth, grant and membership state. */
@Service
@RequiredArgsConstructor
public class McpReferenceTokenVerifier implements McpBearerTokenVerifier {
  private final OAuth2AuthorizationService authorizations;
  private final RegisteredClientRepository clients;
  private final McpTokenFamilies families;
  private final McpGrantReader grants;
  private final WorkspaceMembershipReader memberships;
  private final McpEndpointProperties endpoints;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public Optional<McpAuthenticationContext> verify(String bearerToken) {
    if (bearerToken == null || bearerToken.isBlank()) {
      return Optional.empty();
    }
    // The authorization service hashes the presented value and restricts lookup to access tokens.
    OAuth2Authorization authorization =
        authorizations.findByToken(bearerToken, OAuth2TokenType.ACCESS_TOKEN);
    if (authorization == null || authorization.getAccessToken() == null) {
      return Optional.empty();
    }
    var access = authorization.getAccessToken();
    Instant now = clock.instant();
    if (access.isInvalidated()
        || !now.isBefore(access.getToken().getExpiresAt())
        || now.isBefore(access.getToken().getIssuedAt())) {
      return Optional.empty();
    }
    OAuth2AuthorizationRequest request =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (request == null
        || !endpoints
            .resourceUri()
            .toString()
            .equals(request.getAdditionalParameters().get("resource"))) {
      return Optional.empty();
    }
    UUID grantId = families.grantId(authorization.getId());
    McpGrantDetail grant = grantId == null ? null : grants.findActive(grantId).orElse(null);
    if (grant == null || !grant.userId().toString().equals(authorization.getPrincipalName())) {
      return Optional.empty();
    }
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    Set<String> scopes = access.getToken().getScopes();
    if (client == null
        || !grant.clientId().equals(client.getClientId())
        || scopes.isEmpty()
        || !grant.scopes().containsAll(scopes)
        || !authorization.getAuthorizedScopes().containsAll(scopes)
        || memberships.roleOf(grant.workspaceId(), grant.userId()).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new McpAuthenticationContext(
            grant.id(), grant.userId(), grant.clientId(), grant.workspaceId(), scopes));
  }
}
