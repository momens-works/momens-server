package works.momens.server.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * JDBC persistence for MCP authorizations that never stores bearer material in plain text.
 *
 * <p>Spring Authorization Server's JDBC implementation stores token values as supplied. MCP uses
 * reference tokens, so the persisted values are SHA-256 digests while lookup hashes the presented
 * value before delegating to the standard JDBC queries.
 */
final class McpOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

  McpOAuth2AuthorizationService(
      JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
    super(jdbcOperations, registeredClientRepository);
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    super.save(maskTokenValues(authorization));
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
    if (tokenType == null || "state".equals(tokenType.getValue())) {
      OAuth2Authorization authorization = super.findByToken(token, tokenType);
      if (authorization != null || "state".equals(tokenType.getValue())) {
        return authorization;
      }
    }
    OAuth2Authorization authorization = super.findByToken(hash(token), tokenType);
    return authorization == null ? null : restorePresentedToken(authorization, token);
  }

  private static OAuth2Authorization maskTokenValues(OAuth2Authorization authorization) {
    OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    maskCode(builder, authorization.getToken(OAuth2AuthorizationCode.class));
    maskAccessToken(builder, authorization.getAccessToken());
    maskRefreshToken(builder, authorization.getRefreshToken());
    return builder.build();
  }

  private static void maskCode(
      OAuth2Authorization.Builder builder,
      OAuth2Authorization.Token<OAuth2AuthorizationCode> token) {
    if (token != null) {
      OAuth2AuthorizationCode code = token.getToken();
      builder.token(
          new OAuth2AuthorizationCode(
              hash(code.getTokenValue()), code.getIssuedAt(), code.getExpiresAt()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static void maskAccessToken(
      OAuth2Authorization.Builder builder, OAuth2Authorization.Token<OAuth2AccessToken> token) {
    if (token != null) {
      OAuth2AccessToken accessToken = token.getToken();
      builder.token(
          new OAuth2AccessToken(
              accessToken.getTokenType(),
              hash(accessToken.getTokenValue()),
              accessToken.getIssuedAt(),
              accessToken.getExpiresAt(),
              accessToken.getScopes()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static void maskRefreshToken(
      OAuth2Authorization.Builder builder, OAuth2Authorization.Token<OAuth2RefreshToken> token) {
    if (token != null) {
      OAuth2RefreshToken refreshToken = token.getToken();
      builder.token(
          new OAuth2RefreshToken(
              hash(refreshToken.getTokenValue()),
              refreshToken.getIssuedAt(),
              refreshToken.getExpiresAt()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static OAuth2Authorization restorePresentedToken(
      OAuth2Authorization authorization, String presentedToken) {
    String digest = hash(presentedToken);
    OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    restoreCode(
        builder, authorization.getToken(OAuth2AuthorizationCode.class), digest, presentedToken);
    restoreAccessToken(builder, authorization.getAccessToken(), digest, presentedToken);
    restoreRefreshToken(builder, authorization.getRefreshToken(), digest, presentedToken);
    return builder.build();
  }

  private static void restoreCode(
      OAuth2Authorization.Builder builder,
      OAuth2Authorization.Token<OAuth2AuthorizationCode> token,
      String digest,
      String presentedToken) {
    if (token != null && token.getToken().getTokenValue().equals(digest)) {
      OAuth2AuthorizationCode code = token.getToken();
      builder.token(
          new OAuth2AuthorizationCode(presentedToken, code.getIssuedAt(), code.getExpiresAt()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static void restoreAccessToken(
      OAuth2Authorization.Builder builder,
      OAuth2Authorization.Token<OAuth2AccessToken> token,
      String digest,
      String presentedToken) {
    if (token != null && token.getToken().getTokenValue().equals(digest)) {
      OAuth2AccessToken accessToken = token.getToken();
      builder.token(
          new OAuth2AccessToken(
              accessToken.getTokenType(),
              presentedToken,
              accessToken.getIssuedAt(),
              accessToken.getExpiresAt(),
              accessToken.getScopes()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static void restoreRefreshToken(
      OAuth2Authorization.Builder builder,
      OAuth2Authorization.Token<OAuth2RefreshToken> token,
      String digest,
      String presentedToken) {
    if (token != null && token.getToken().getTokenValue().equals(digest)) {
      OAuth2RefreshToken refreshToken = token.getToken();
      builder.token(
          new OAuth2RefreshToken(
              presentedToken, refreshToken.getIssuedAt(), refreshToken.getExpiresAt()),
          metadata -> metadata.putAll(token.getMetadata()));
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
