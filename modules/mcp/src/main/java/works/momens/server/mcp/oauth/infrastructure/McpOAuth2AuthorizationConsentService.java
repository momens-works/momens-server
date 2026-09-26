package works.momens.server.mcp.oauth.infrastructure;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.annotation.Transactional;

/** Serializes SAS's read-then-insert consent save on its client/principal key. */
class McpOAuth2AuthorizationConsentService extends JdbcOAuth2AuthorizationConsentService {
  private final JdbcOperations jdbc;

  McpOAuth2AuthorizationConsentService(JdbcOperations jdbc, RegisteredClientRepository clients) {
    super(jdbc, clients);
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(OAuth2AuthorizationConsent consent) {
    // Workspace is deliberately absent: SAS consent is shared across a user's workspace grants.
    jdbc.queryForObject(
        "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
        Integer.class,
        "mcp-consent:" + consent.getRegisteredClientId() + ":" + consent.getPrincipalName());
    super.save(consent);
  }
}
