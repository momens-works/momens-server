package works.momens.server.mcp.oauth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
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
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** Resolves reference tokens against the current OAuth, grant and membership state. */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpReferenceTokenVerifier implements McpBearerTokenVerifier {
  private final OAuth2AuthorizationService authorizations;
  private final RegisteredClientRepository clients;
  private final McpTokenFamilies families;
  private final McpGrantReader grants;
  private final McpGrantWriter grantWriter;
  private final WorkspaceMembershipReader memberships;
  private final McpEndpointProperties endpoints;
  private final Clock clock;

  @Override
  @Transactional
  public Optional<McpAuthenticationContext> verify(String bearerToken) {
    if (bearerToken == null || bearerToken.isBlank()) {
      return reject("empty_token");
    }
    // The authorization service hashes the presented value and restricts lookup to access tokens.
    OAuth2Authorization authorization =
        authorizations.findByToken(bearerToken, OAuth2TokenType.ACCESS_TOKEN);
    if (authorization == null || authorization.getAccessToken() == null) {
      return reject("access_token_not_found");
    }
    OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
    Instant now = clock.instant();
    if (access.isInvalidated()) {
      return reject("token_invalidated");
    }
    if (!now.isBefore(access.getToken().getExpiresAt())) {
      return reject("token_expired");
    }
    if (now.isBefore(access.getToken().getIssuedAt())) {
      return reject("token_not_yet_valid");
    }
    OAuth2AuthorizationRequest request =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (request == null
        || !endpoints
            .resourceUri()
            .toString()
            .equals(request.getAdditionalParameters().get("resource"))) {
      return reject("resource_mismatch_or_missing");
    }
    UUID grantId = families.grantId(authorization.getId());
    if (grantId == null) {
      return reject("active_family_not_found");
    }
    McpGrantDetail grant = grants.findActive(grantId).orElse(null);
    if (grant == null) {
      return reject("active_grant_not_found");
    }
    if (!grant.userId().toString().equals(authorization.getPrincipalName())) {
      return reject("grant_user_mismatch");
    }
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    Set<String> scopes = access.getToken().getScopes();
    if (client == null) {
      return reject("client_not_found");
    }
    if (!grant.clientId().equals(client.getClientId())) {
      return reject("grant_client_mismatch");
    }
    if (scopes.isEmpty()) {
      return reject("token_scopes_empty");
    }
    if (!grant.scopes().containsAll(scopes)) {
      return reject("scopes_outside_grant");
    }
    if (!authorization.getAuthorizedScopes().containsAll(scopes)) {
      return reject("scopes_outside_authorization");
    }
    if (memberships.roleOf(grant.workspaceId(), grant.userId()).isEmpty()) {
      return reject("workspace_membership_not_found");
    }
    if (!grantWriter.recordUsage(grant.id(), now)) {
      return reject("active_grant_not_found");
    }
    return Optional.of(
        new McpAuthenticationContext(
            grant.id(), grant.userId(), grant.clientId(), grant.workspaceId(), scopes));
  }

  private Optional<McpAuthenticationContext> reject(String reason) {
    log.info("event=mcp_oauth_access_verification outcome=rejected reason={}", reason);
    return Optional.empty();
  }
}
