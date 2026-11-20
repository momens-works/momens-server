package works.momens.server.mcp.oauth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.mcp.oauth.application.McpTokenFamilies;

/** One SAS authorization is one refresh family; history contains digests only. */
@Component
@RequiredArgsConstructor
class McpTokenFamilyStore implements McpTokenFamilyRevoker, McpTokenFamilies {
  private final JdbcOperations jdbc;
  private final OAuth2AuthorizationService authorizations;

  @Override
  public void create(String authorizationId, UUID grantId) {
    jdbc.update(
        "INSERT INTO mcp_token_families (id, authorization_id, grant_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        authorizationId,
        grantId);
  }

  @Override
  public boolean lock(String authorizationId) {
    return !jdbc.queryForList(
            "SELECT id FROM oauth2_authorization WHERE id = ? FOR UPDATE",
            String.class,
            authorizationId)
        .isEmpty();
  }

  @Override
  public UUID grantId(String authorizationId) {
    List<UUID> ids =
        jdbc.queryForList(
            "SELECT grant_id FROM mcp_token_families WHERE authorization_id = ? AND revoked_at IS NULL",
            UUID.class,
            authorizationId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  @Override
  public String findRefreshFamily(String rawToken) {
    List<String> ids =
        jdbc.queryForList(
            "SELECT authorization_id FROM mcp_refresh_token_history WHERE token_hash = ?",
            String.class,
            McpOAuth2AuthorizationService.hash(rawToken));
    return ids.isEmpty() ? null : ids.getFirst();
  }

  @Override
  public void rememberRefresh(String digest, String authorizationId) {
    jdbc.update(
        "INSERT INTO mcp_refresh_token_history (id, token_hash, authorization_id) VALUES (?, ?, ?) "
            + "ON CONFLICT (token_hash) DO NOTHING",
        UUID.randomUUID(),
        digest,
        authorizationId);
  }

  @Override
  public void revoke(String authorizationId, Instant at) {
    lock(authorizationId);
    jdbc.update(
        "UPDATE mcp_token_families SET revoked_at = COALESCE(revoked_at, ?), updated_at = ? "
            + "WHERE authorization_id = ?",
        Timestamp.from(at),
        Timestamp.from(at),
        authorizationId);
    OAuth2Authorization authorization = authorizations.findById(authorizationId);
    if (authorization == null) {
      return;
    }
    OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    if (authorization.getAccessToken() != null) {
      builder.invalidate(authorization.getAccessToken().getToken());
    }
    if (authorization.getRefreshToken() != null) {
      builder.invalidate(authorization.getRefreshToken().getToken());
    }
    if (authorization.getToken(OAuth2AuthorizationCode.class) != null) {
      builder.invalidate(authorization.getToken(OAuth2AuthorizationCode.class).getToken());
    }
    authorizations.save(builder.build());
  }

  @Override
  @Transactional
  public void revokeByGrantId(UUID grantId, Instant revokedAt) {
    for (String id :
        jdbc.queryForList(
            "SELECT authorization_id FROM mcp_token_families WHERE grant_id = ? ORDER BY authorization_id",
            String.class,
            grantId)) {
      revoke(id, revokedAt);
    }
  }
}
