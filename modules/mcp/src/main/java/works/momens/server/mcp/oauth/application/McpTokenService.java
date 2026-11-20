package works.momens.server.mcp.oauth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** Serializes each SAS family across code exchange, rotation, reuse and revocation. */
@Slf4j
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
        transactions.execute(status -> {
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
      if (refresh && grantId == null) {
        log.info(
            "event=mcp_oauth_refresh outcome=rejected reason=family_inactive authorization_id={} presented_token_id={}",
            id,
            tokenId(raw));
      }
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    // Look up again under the row lock: another request may just have rotated the token.
    OAuth2Authorization presented = authorizations.findByToken(raw, type);
    if (refresh && (presented == null || !presented.getRefreshToken().isActive())) {
      families.revoke(id, Instant.now());
      log.warn(
          "event=mcp_oauth_refresh outcome=family_revoked reason=refresh_token_reused authorization_id={} grant_id={} presented_token_id={} current_token_issued_at={}",
          id,
          grantId,
          tokenId(raw),
          current.getRefreshToken() == null
              ? null
              : current.getRefreshToken().getToken().getIssuedAt());
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    Authentication result = delegate.authenticate(authentication);
    OAuth2Authorization saved = authorizations.findById(id);
    if (saved.getRefreshToken() != null) {
      families.rememberRefresh(saved.getRefreshToken().getToken().getTokenValue(), id);
    }
    if (refresh) {
      OAuth2AccessTokenAuthenticationToken issued = (OAuth2AccessTokenAuthenticationToken) result;
      log.info(
          "event=mcp_oauth_refresh outcome=succeeded authorization_id={} grant_id={} presented_token_id={} presented_token_issued_at={} next_token_id={} next_token_issued_at={}",
          id,
          grantId,
          tokenId(raw),
          presented.getRefreshToken().getToken().getIssuedAt(),
          tokenId(issued.getRefreshToken().getTokenValue()),
          saved.getRefreshToken().getToken().getIssuedAt());
    }
    return result;
  }

  private static String tokenId(String raw) {
    // Short digest for correlating rotations; never log the raw token or its full persisted hash.
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private void validateResource(Object requested) {
    if (!resource.equals(requested)) {
      throw new OAuth2AuthenticationException("invalid_target");
    }
  }

  private record Result(Authentication authentication, OAuth2AuthenticationException error) {}
}
