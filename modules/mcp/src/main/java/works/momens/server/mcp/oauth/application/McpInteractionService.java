package works.momens.server.mcp.oauth.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import works.momens.server.mcp.configuration.McpOAuthProperties;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** Consent UI state lives in SAS authorization attributes, separate from McpGrant. */
@Service
@RequiredArgsConstructor
public class McpInteractionService {
  private static final String EXPIRES_AT = "mcp.interaction.expires_at";
  private static final String DECIDED = "mcp.interaction.decided";
  private final OAuth2AuthorizationService authorizations;
  private final OAuth2AuthorizationConsentService consents;
  private final RegisteredClientRepository clients;
  private final McpGrantWriter grants;
  private final WorkspaceReader workspaces;
  private final WorkspaceMembershipReader memberships;
  private final McpTokenFamilies families;
  private final McpOAuthProperties properties;
  private final Clock clock;
  private final Base64StringKeyGenerator secrets =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

  @Transactional
  public String begin(OAuth2AuthorizationCodeRequestAuthenticationToken token) {
    URI consentUri = properties.consentUri();
    if (consentUri == null || !consentUri.isAbsolute() || consentUri.getHost() == null) {
      throw new OAuth2AuthenticationException("server_error");
    }
    RegisteredClient client = clients.findByClientId(token.getClientId());
    OAuth2AuthorizationRequest request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(token.getAuthorizationUri())
            .clientId(token.getClientId())
            .redirectUri(token.getRedirectUri())
            .state(token.getState())
            .scopes(token.getScopes().isEmpty() ? client.getScopes() : token.getScopes())
            .additionalParameters(
                Map.of(
                    "code_challenge",
                    token.getAdditionalParameters().get("code_challenge"),
                    "code_challenge_method",
                    "S256",
                    "resource",
                    token.getAdditionalParameters().get("resource")))
            .build();
    String id = UUID.randomUUID().toString();
    authorizations.save(
        OAuth2Authorization.withRegisteredClient(client)
            .id(id)
            .principalName("pending:" + id)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .attribute(OAuth2AuthorizationRequest.class.getName(), request)
            .attribute(EXPIRES_AT, clock.instant().plusSeconds(600).toString())
            .build());
    return UriComponentsBuilder.fromUri(consentUri)
        .queryParam("interaction", id)
        .build()
        .toUriString();
  }

  @Transactional(readOnly = true)
  public InteractionView get(UUID id, UUID userId) {
    OAuth2Authorization authorization = requireOpen(id);
    OAuth2AuthorizationRequest request =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    return new InteractionView(
        id,
        client.getClientName(),
        URI.create(request.getRedirectUri()).getAuthority(),
        request.getScopes().stream().toList(),
        workspaces.listByMemberUserId(userId).stream()
            .map(
                workspace ->
                    new WorkspaceOption(workspace.id(), workspace.name(), workspace.slug()))
            .toList(),
        expiresAt(authorization));
  }

  @Transactional
  public String approve(UUID id, UUID userId, UUID workspaceId) {
    families.lock(id.toString());
    OAuth2Authorization authorization = requireOpen(id);
    if (memberships.roleOf(workspaceId, userId).isEmpty()) {
      throw new McpInteractionException(
          McpInteractionException.Reason.WORKSPACE_MEMBERSHIP_REQUIRED);
    }
    OAuth2AuthorizationRequest request =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
    McpGrantDetail grant =
        grants.replace(
            new CreateMcpGrantCommand(
                userId, client.getClientId(), workspaceId, request.getScopes()));
    Instant now = clock.instant();
    OAuth2AuthorizationCode code =
        new OAuth2AuthorizationCode(
            secrets.generateKey(),
            now,
            now.plus(client.getTokenSettings().getAuthorizationCodeTimeToLive()));
    authorizations.save(
        OAuth2Authorization.from(authorization)
            .principalName(userId.toString())
            .attribute(
                Principal.class.getName(),
                UsernamePasswordAuthenticationToken.authenticated(
                    userId.toString(), null, List.of()))
            .attribute(DECIDED, true)
            .authorizedScopes(request.getScopes())
            .token(code)
            .build());
    OAuth2AuthorizationConsent.Builder consent =
        OAuth2AuthorizationConsent.withId(client.getId(), userId.toString());
    request.getScopes().forEach(consent::scope);
    consents.save(consent.build());
    families.create(authorization.getId(), grant.id());
    return redirect(request, "code", code.getTokenValue());
  }

  @Transactional
  public String deny(UUID id, UUID userId) {
    families.lock(id.toString());
    OAuth2Authorization authorization = requireOpen(id);
    authorizations.save(
        OAuth2Authorization.from(authorization)
            .principalName(userId.toString())
            .attribute(DECIDED, true)
            .build());
    return redirect(
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName()),
        "error",
        "access_denied");
  }

  private OAuth2Authorization requireOpen(UUID id) {
    OAuth2Authorization authorization = authorizations.findById(id.toString());
    if (authorization == null || authorization.getAttribute(EXPIRES_AT) == null) {
      throw new McpInteractionException(McpInteractionException.Reason.INVALID_INTERACTION);
    }
    if (!clock.instant().isBefore(expiresAt(authorization))) {
      throw new McpInteractionException(McpInteractionException.Reason.EXPIRED);
    }
    if (Boolean.TRUE.equals(authorization.getAttribute(DECIDED))) {
      throw new McpInteractionException(McpInteractionException.Reason.ALREADY_DECIDED);
    }
    return authorization;
  }

  private static Instant expiresAt(OAuth2Authorization authorization) {
    return Instant.parse(authorization.getAttribute(EXPIRES_AT));
  }

  private static String redirect(OAuth2AuthorizationRequest request, String key, String value) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString(request.getRedirectUri())
            .replaceQueryParam(key, UriUtils.encode(value, StandardCharsets.UTF_8));
    if ("error".equals(key)) {
      builder.replaceQueryParam(
          "error_description",
          UriUtils.encode("The resource owner denied the request", StandardCharsets.UTF_8));
    }
    if (request.getState() != null) {
      builder.replaceQueryParam(
          "state", UriUtils.encode(request.getState(), StandardCharsets.UTF_8));
    }
    return builder.build(true).toUriString();
  }

  public record InteractionView(
      UUID interactionId,
      String clientName,
      String redirectHost,
      List<String> scopes,
      List<WorkspaceOption> workspaces,
      Instant expiresAt) {}

  public record WorkspaceOption(UUID id, String name, String slug) {}
}
