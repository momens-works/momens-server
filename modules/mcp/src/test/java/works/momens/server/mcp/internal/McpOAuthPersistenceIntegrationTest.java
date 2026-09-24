package works.momens.server.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.ContextConfiguration;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = McpOAuthPersistenceIntegrationTest.TestApplication.class)
@Import(McpOAuthPersistenceConfig.class)
@DisplayName("MCP OAuth 표준 persistence 통합 테스트")
class McpOAuthPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant ISSUED_AT = Instant.parse("2026-09-24T00:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-09-24T01:00:00Z");

  @Autowired private RegisteredClientRepository registeredClientRepository;
  @Autowired private OAuth2AuthorizationService authorizationService;
  @Autowired private OAuth2AuthorizationConsentService consentService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("RegisteredClient와 OAuth2AuthorizationConsent를 저장하고 조회한다")
  void persistsRegisteredClientAndConsent() {
    RegisteredClient client = registeredClient();

    registeredClientRepository.save(client);
    OAuth2AuthorizationConsent consent =
        OAuth2AuthorizationConsent.withId(client.getId(), "user-1")
            .scope("mcp:projects:read")
            .authority(new SimpleGrantedAuthority("ROLE_USER"))
            .build();
    consentService.save(consent);

    RegisteredClient persistedClient =
        registeredClientRepository.findByClientId(client.getClientId());
    OAuth2AuthorizationConsent persistedConsent = consentService.findById(client.getId(), "user-1");

    assertThat(persistedClient).isEqualTo(client);
    assertThat(persistedConsent).isEqualTo(consent);
  }

  @Test
  @DisplayName("authorization code·access token·refresh token lifecycle을 저장하고 조회한다")
  void persistsAuthorizationTokenLifecycle() {
    RegisteredClient client = registeredClient();
    registeredClientRepository.save(client);

    OAuth2Authorization authorization =
        OAuth2Authorization.withRegisteredClient(client)
            .principalName("user-1")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("mcp:projects:read"))
            .token(new OAuth2AuthorizationCode("authorization-code", ISSUED_AT, EXPIRES_AT))
            .accessToken(
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "access-token",
                    ISSUED_AT,
                    EXPIRES_AT,
                    Set.of("mcp:projects:read")))
            .refreshToken(new OAuth2RefreshToken("refresh-token", ISSUED_AT, EXPIRES_AT))
            .build();

    authorizationService.save(authorization);

    assertThat(authorizationService.findByToken("authorization-code", null)).isNotNull();
    OAuth2Authorization persistedAccessToken =
        authorizationService.findByToken("access-token", OAuth2TokenType.ACCESS_TOKEN);
    assertThat(authorizationService.findByToken("refresh-token", OAuth2TokenType.REFRESH_TOKEN))
        .isNotNull();
    assertThat(persistedAccessToken).isNotNull();
    assertThat(persistedAccessToken.getAccessToken().getToken().getTokenValue())
        .isEqualTo("access-token");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT access_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId()))
        .isEqualTo(sha256("access-token"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT authorization_code_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId()))
        .isEqualTo(sha256("authorization-code"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT refresh_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId()))
        .isEqualTo(sha256("refresh-token"));
  }

  private static RegisteredClient registeredClient() {
    return RegisteredClient.withId("registered-client-1")
        .clientId("mcp-client-1")
        .clientIdIssuedAt(ISSUED_AT)
        .clientName("MCP client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUri("http://127.0.0.1:43110/callback")
        .scope("mcp:projects:read")
        .clientSettings(ClientSettings.builder().requireProofKey(true).build())
        .tokenSettings(TokenSettings.builder().build())
        .build();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @org.springframework.boot.SpringBootConfiguration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  static class TestApplication {}
}
