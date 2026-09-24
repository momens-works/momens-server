package works.momens.server.mcp.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import works.momens.server.mcp.grant.McpScope;

/**
 * Registers every MCP client as a public PKCE client with reference access tokens and rotated
 * refresh tokens (ADR-0023). Token lifetimes match the legacy MCP authorization server.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class McpRegisteredClientConverter
    implements Converter<OAuth2ClientRegistration, RegisteredClient> {

  private static final StringKeyGenerator CLIENT_ID_GENERATOR =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);
  private static final Duration AUTHORIZATION_CODE_TTL = Duration.ofMinutes(5);
  private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);
  private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(90);

  private final Clock clock;

  @Override
  public RegisteredClient convert(OAuth2ClientRegistration registration) {
    return RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(CLIENT_ID_GENERATOR.generateKey())
        .clientIdIssuedAt(clock.instant())
        .clientName(registration.getClientName().strip())
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUris(redirectUris -> redirectUris.addAll(registration.getRedirectUris()))
        .scopes(
            scopes -> {
              for (McpScope scope : McpScope.values()) {
                scopes.add(scope.value());
              }
            })
        .clientSettings(
            ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build())
        .tokenSettings(
            TokenSettings.builder()
                .authorizationCodeTimeToLive(AUTHORIZATION_CODE_TTL)
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                .reuseRefreshTokens(false)
                .build())
        .build();
  }
}
