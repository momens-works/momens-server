package works.momens.server.source.connection.oauth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 전환 중 양쪽 서버가 발급한 state를 검증할 수 있도록 서명 키·issuer·audience·claim 이름은 레거시와 같게 유지합니다.
 *
 * <p>issuer는 URL이 아닌 {@code momens-api}이므로 URL로 해석하는 표준 접근자 대신 문자열로 읽습니다. 만료는 주입한 시계로 직접 검증합니다. 난수
 * jti는 포함하지만 소비 이력을 저장하지 않아 일회성 사용을 보장하지 않습니다.
 */
class OAuthStateSigner {

  static final String ISSUER = "momens-api";
  static final String AUDIENCE = "source-oauth";
  static final String ISSUER_CLAIM = "iss";
  static final String WORKSPACE_ID_CLAIM = "workspace_id";
  static final String PROVIDER_CLAIM = "provider";

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int NONCE_BYTES = 16;

  private final NimbusJwtEncoder encoder;
  private final NimbusJwtDecoder decoder;
  private final Duration ttl;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  private OAuthStateSigner() {
    this.encoder = null;
    this.decoder = null;
    this.ttl = Duration.ZERO;
    this.clock = Clock.systemUTC();
  }

  static OAuthStateSigner unavailable() {
    return new OAuthStateSigner();
  }

  OAuthStateSigner(String secret, Duration ttl, Clock clock) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    this.decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
    this.ttl = ttl;
    this.clock = clock;
  }

  String sign(OAuthState state) {
    requireConfigured();
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(state.userId().toString())
            .audience(List.of(AUDIENCE))
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .id(nonce())
            .claim(WORKSPACE_ID_CLAIM, state.workspaceId().toString())
            .claim(PROVIDER_CLAIM, state.provider())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * 검증에 실패하면 예외를 던지지 않고 {@code null}을 반환합니다.
   *
   * <p>호출하는 쪽에서는 서명 불일치, 만료, 필수 claim 누락을 구분할 필요가 없습니다. 레거시도 이 경우를 하나의 오류로 처리합니다.
   */
  OAuthState verify(String token) {
    if (decoder == null) {
      return null;
    }
    Jwt jwt;
    try {
      jwt = decoder.decode(token);
    } catch (JwtException e) {
      return null;
    }
    if (!ISSUER.equals(jwt.getClaimAsString(ISSUER_CLAIM))) {
      return null;
    }
    if (jwt.getAudience() == null || !jwt.getAudience().contains(AUDIENCE)) {
      return null;
    }
    if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
      return null;
    }
    UUID workspaceId = parseUuid(jwt.getClaimAsString(WORKSPACE_ID_CLAIM));
    UUID userId = parseUuid(jwt.getSubject());
    String provider = normalize(jwt.getClaimAsString(PROVIDER_CLAIM));
    if (workspaceId == null || userId == null || provider.isEmpty()) {
      return null;
    }
    return new OAuthState(workspaceId, userId, provider);
  }

  private void requireConfigured() {
    if (encoder == null) {
      throw new IllegalStateException("source oauth state secret is not configured");
    }
  }

  static String normalize(String provider) {
    return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
  }

  private static UUID parseUuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String nonce() {
    byte[] bytes = new byte[NONCE_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
