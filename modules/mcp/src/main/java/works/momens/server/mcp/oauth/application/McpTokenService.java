package works.momens.server.mcp.oauth.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** Serializes each SAS family across code exchange, rotation, reuse and revocation. */
public final class McpTokenService {
  private final OAuth2AuthorizationService authorizations;
  private final McpTokenFamilies families;
  private final McpGrantReader grants;
  private final WorkspaceMembershipReader memberships;
  private final TransactionTemplate transactions;
  private final String resource;

  public McpTokenService(
      OAuth2AuthorizationService authorizations,
      McpTokenFamilies families,
      McpGrantReader grants,
      WorkspaceMembershipReader memberships,
      TransactionTemplate transactions,
      String resource) {
    this.authorizations = authorizations;
    this.families = families;
    this.grants = grants;
    this.memberships = memberships;
    this.transactions = transactions;
    this.resource = resource;
  }

  public Authentication authenticate(
      Authentication authentication, AuthenticationProvider delegate) {
    // Commit security invalidations even when SAS rejects a replayed grant.
    Result result =
        transactions.execute(
            status -> {
              try {
                return new Result(authenticateLocked(authentication, delegate), null);
              } catch (OAuth2AuthenticationException exception) {
                return new Result(null, exception);
              }
            });
    if (result.error() != null) {
      throw result.error();
    }
    return result.authentication();
  }

  private Authentication authenticateLocked(
      Authentication authentication, AuthenticationProvider delegate) {
    if (!(authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()
        || client.getRegisteredClient() == null) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
    String raw;
    OAuth2TokenType type;
    boolean revoke = authentication instanceof OAuth2TokenRevocationAuthenticationToken;
    boolean refresh = authentication instanceof OAuth2RefreshTokenAuthenticationToken;
    if (authentication instanceof OAuth2AuthorizationCodeAuthenticationToken code) {
      raw = code.getCode();
      type = new OAuth2TokenType("code");
      validateResource(code.getAdditionalParameters().get("resource"));
      Object verifier = code.getAdditionalParameters().get("code_verifier");
      if (!(verifier instanceof String value) || !value.matches("[A-Za-z0-9._~-]{43,128}")) {
        throw new OAuth2AuthenticationException("invalid_grant");
      }
    } else if (authentication instanceof OAuth2RefreshTokenAuthenticationToken token) {
      raw = token.getRefreshToken();
      type = OAuth2TokenType.REFRESH_TOKEN;
      validateResource(token.getAdditionalParameters().get("resource"));
    } else {
      raw = ((OAuth2TokenRevocationAuthenticationToken) authentication).getToken();
      type = null;
    }
    OAuth2Authorization found = authorizations.findByToken(raw, type);
    String id = found == null ? families.findRefreshFamily(raw) : found.getId();
    if (id == null || !families.lock(id)) {
      if (revoke) {
        return authentication;
      }
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    OAuth2Authorization current = authorizations.findById(id);
    if (!current.getRegisteredClientId().equals(client.getRegisteredClient().getId())) {
      if (revoke) {
        return authentication;
      }
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    if (revoke) {
      families.revoke(id, Instant.now());
      return authentication;
    }
    OAuth2AuthorizationRequest authorizationRequest =
        current.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (authorizationRequest == null
        || !resource.equals(authorizationRequest.getAdditionalParameters().get("resource"))) {
      throw new OAuth2AuthenticationException("invalid_target");
    }
    UUID grantId = families.grantId(id);
    McpGrantDetail grant = grantId == null ? null : grants.findActive(grantId).orElse(null);
    if (grant == null || memberships.roleOf(grant.workspaceId(), grant.userId()).isEmpty()) {
      families.revoke(id, Instant.now());
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    // Look up again under the row lock: another request may just have rotated the token.
    OAuth2Authorization presented = authorizations.findByToken(raw, type);
    if (refresh && (presented == null || !presented.getRefreshToken().isActive())) {
      families.revoke(id, Instant.now());
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    Authentication result = delegate.authenticate(authentication);
    OAuth2Authorization saved = authorizations.findById(id);
    if (saved.getRefreshToken() != null) {
      families.rememberRefresh(saved.getRefreshToken().getToken().getTokenValue(), id);
    }
    return result;
  }

  private void validateResource(Object requested) {
    if (!resource.equals(requested)) {
      throw new OAuth2AuthenticationException("invalid_target");
    }
  }

  private record Result(Authentication authentication, OAuth2AuthenticationException error) {}
}
