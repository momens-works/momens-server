package works.momens.server.mcp.oauth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@Configuration
class McpOAuthPersistenceConfig {

  @Bean
  RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
    return new JdbcRegisteredClientRepository(jdbcOperations);
  }

  @Bean
  OAuth2AuthorizationService oAuth2AuthorizationService(
      JdbcOperations jdbcOperations,
      RegisteredClientRepository registeredClientRepository,
      McpUnusedClientRepository unusedClients) {
    return new McpOAuth2AuthorizationService(
        jdbcOperations, registeredClientRepository, unusedClients);
  }

  @Bean
  OAuth2AuthorizationConsentService oAuth2AuthorizationConsentService(
      JdbcOperations jdbcOperations,
      RegisteredClientRepository registeredClientRepository,
      McpUnusedClientRepository unusedClients) {
    return new McpOAuth2AuthorizationConsentService(
        jdbcOperations, registeredClientRepository, unusedClients);
  }
}
