package works.momens.server.mcp.oauth.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** SAS reference access tokens and rotated refresh tokens, including public PKCE clients. */
@RequiredArgsConstructor
final class McpTokenGenerator implements OAuth2TokenGenerator<OAuth2Token> {
  private final Clock clock;
  private final OAuth2AccessTokenGenerator accessTokens = new OAuth2AccessTokenGenerator();
  private final Base64StringKeyGenerator secrets =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

  @Override
  public OAuth2Token generate(OAuth2TokenContext context) {
    if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
      Instant now = clock.instant();
      return new OAuth2RefreshToken(
          secrets.generateKey(),
          now,
          now.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));
    }
    return accessTokens.generate(context);
  }
}
