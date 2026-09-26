package works.momens.server.mcp.oauth.application;

import java.time.Instant;
import java.util.UUID;

/** Persistence operations used within the OAuth application transaction. */
public interface McpTokenFamilies {
  void create(String authorizationId, UUID grantId);

  boolean lock(String authorizationId);

  UUID grantId(String authorizationId);

  String findRefreshFamily(String rawToken);

  void rememberRefresh(String digest, String authorizationId);

  void revoke(String authorizationId, Instant at);
}
