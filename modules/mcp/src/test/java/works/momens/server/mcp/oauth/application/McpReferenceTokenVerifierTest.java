package works.momens.server.mcp.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import works.momens.server.mcp.configuration.McpEndpointProperties;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@ExtendWith(MockitoExtension.class)
class McpReferenceTokenVerifierTest {
  private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
  private static final String RESOURCE = "https://api.momens.works/api/mcp";
  private static final String RAW = "reference-token";
  private static final String READ = McpScope.TASKS_READ.value();
  private final UUID userId = UUID.randomUUID();
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID grantId = UUID.randomUUID();
  private final RegisteredClient client =
      RegisteredClient.withId("registered-id")
          .clientId("public-client")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/callback")
          .scope(READ)
          .build();
  @Mock OAuth2AuthorizationService authorizations;
  @Mock RegisteredClientRepository clients;
  @Mock McpTokenFamilies families;
  @Mock McpGrantReader grants;
  @Mock WorkspaceMembershipReader memberships;
  McpReferenceTokenVerifier verifier;

  @BeforeEach
  void setup() {
    verifier =
        new McpReferenceTokenVerifier(
            authorizations,
            clients,
            families,
            grants,
            memberships,
            new McpEndpointProperties(URI.create(RESOURCE), List.of()),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void returnsOnlyTokenScopesAndTheVerifiedWorkspaceContext() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    stubGrant(userId, "public-client", List.of(READ, McpScope.TASKS_WRITE.value()));
    when(clients.findById(client.getId())).thenReturn(client);
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    McpAuthenticationContext context = verifier.verify(RAW).orElseThrow();
    assertThat(context.grantId()).isEqualTo(grantId);
    assertThat(context.userId()).isEqualTo(userId);
    assertThat(context.clientId()).isEqualTo(client.getClientId());
    assertThat(context.workspaceId()).isEqualTo(workspaceId);
    assertThat(context.scopes()).containsExactly(READ);
    assertThat(context.permits(workspaceId, McpScope.TASKS_READ)).isTrue();
    assertThat(context.permits(UUID.randomUUID(), McpScope.TASKS_READ)).isFalse();
    assertThat(context.permits(workspaceId, McpScope.TASKS_WRITE)).isFalse();
    assertThat(context.toString()).doesNotContain(RAW);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsEmptyTokensWithoutLookup(String token) {
    assertThat(verifier.verify(token)).isEmpty();
    verifyNoInteractions(authorizations, families, grants, clients, memberships);
  }

  @Test
  void rejectsUnknownTokens() {
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(families, grants, clients, memberships);
  }

  @Test
  void rejectsExpiredTokenAtTheExactExpiryBoundary() {
    stubToken(authorization(NOW.minusSeconds(60), NOW).build());
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(families, grants);
  }

  @Test
  void rejectsFutureAndInvalidatedTokens() {
    stubToken(authorization(NOW.plusSeconds(1), NOW.plusSeconds(60)).build());
    assertThat(verifier.verify(RAW)).isEmpty();
    OAuth2Authorization active = authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build();
    stubToken(
        OAuth2Authorization.from(active).invalidate(active.getAccessToken().getToken()).build());
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(families, grants);
  }

  @Test
  void rejectsWrongOrMissingResource() {
    stubToken(
        authorization(NOW.minusSeconds(1), NOW.plusSeconds(60))
            .attribute(
                OAuth2AuthorizationRequest.class.getName(), request("https://other.example/mcp"))
            .build());
    assertThat(verifier.verify(RAW)).isEmpty();
    stubToken(
        authorization(NOW.minusSeconds(1), NOW.plusSeconds(60))
            .attributes(attributes -> attributes.remove(OAuth2AuthorizationRequest.class.getName()))
            .build());
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(families, grants);
  }

  @Test
  void rejectsMissingOrRevokedFamilyAndGrant() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    assertThat(verifier.verify(RAW)).isEmpty();
    when(families.grantId("authorization")).thenReturn(grantId);
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(clients, memberships);
  }

  @Test
  void rejectsDifferentGrantUser() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    stubGrant(UUID.randomUUID(), "public-client", List.of(READ));
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(clients, memberships);
  }

  @Test
  void rejectsDifferentOrMissingClient() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    stubGrant(userId, "another-client", List.of(READ));
    assertThat(verifier.verify(RAW)).isEmpty();
    when(clients.findById(client.getId())).thenReturn(client);
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(memberships);
  }

  @Test
  void rejectsScopesOutsideGrantOrAuthorization() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    stubGrant(userId, "public-client", List.of(McpScope.TASKS_WRITE.value()));
    when(clients.findById(client.getId())).thenReturn(client);
    assertThat(verifier.verify(RAW)).isEmpty();
    stubGrant(userId, "public-client", List.of(READ));
    stubToken(
        authorization(NOW.minusSeconds(1), NOW.plusSeconds(60))
            .authorizedScopes(Set.of(McpScope.TASKS_WRITE.value()))
            .build());
    assertThat(verifier.verify(RAW)).isEmpty();
    verifyNoInteractions(memberships);
  }

  @Test
  void rejectsRemovedWorkspaceMembership() {
    stubToken(authorization(NOW.minusSeconds(1), NOW.plusSeconds(60)).build());
    stubGrant(userId, "public-client", List.of(READ));
    when(clients.findById(client.getId())).thenReturn(client);
    assertThat(verifier.verify(RAW)).isEmpty();
  }

  private void stubToken(OAuth2Authorization authorization) {
    when(authorizations.findByToken(RAW, OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);
  }

  private void stubGrant(UUID user, String clientId, List<String> scopes) {
    when(families.grantId("authorization")).thenReturn(grantId);
    when(grants.findActive(grantId))
        .thenReturn(
            Optional.of(
                new McpGrantDetail(grantId, user, clientId, workspaceId, scopes, NOW, null, NOW)));
  }

  private OAuth2Authorization.Builder authorization(Instant issuedAt, Instant expiresAt) {
    return OAuth2Authorization.withRegisteredClient(client)
        .id("authorization")
        .principalName(userId.toString())
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizedScopes(Set.of(READ))
        .attribute(OAuth2AuthorizationRequest.class.getName(), request(RESOURCE))
        .accessToken(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, RAW, issuedAt, expiresAt, Set.of(READ)));
  }

  private OAuth2AuthorizationRequest request(String resource) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://api.momens.works/api/oauth2/authorize")
        .clientId(client.getClientId())
        .redirectUri("http://localhost/callback")
        .additionalParameters(Map.of("resource", resource))
        .build();
  }
}
