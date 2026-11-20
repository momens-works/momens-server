package works.momens.server.mcp.oauth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Repository;

/** Client row locks serialize first authorization with unused registration cleanup. */
@Repository
@RequiredArgsConstructor
class McpUnusedClientRepository {
  private static final String UNUSED =
      """
      NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = c.id)
      AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_consent s WHERE s.registered_client_id = c.id)
      AND NOT EXISTS (SELECT 1 FROM mcp_grants g WHERE g.client_id = c.client_id)
      """;

  static final String LOCK_UNUSED_SQL =
      "SELECT c.id FROM oauth2_registered_client c WHERE c.client_id_issued_at <= ? AND "
          + UNUSED
          + " ORDER BY c.client_id_issued_at, c.id LIMIT ? FOR UPDATE OF c SKIP LOCKED";
  static final String DELETE_UNUSED_SQL =
      "DELETE FROM oauth2_registered_client c WHERE c.id = ? AND " + UNUSED;

  private final JdbcOperations jdbc;

  List<String> lockUnusedBefore(Instant cutoff, int limit) {
    return jdbc.queryForList(LOCK_UNUSED_SQL, String.class, Timestamp.from(cutoff), limit);
  }

  int deleteIfUnused(String id) {
    // A separate statement sees authorizations committed after the candidate query's snapshot.
    return jdbc.update(DELETE_UNUSED_SQL, id);
  }

  void lockForUse(String id) {
    if (jdbc.queryForList(
            "SELECT id FROM oauth2_registered_client WHERE id = ? FOR KEY SHARE", String.class, id)
        .isEmpty()) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
  }
}
