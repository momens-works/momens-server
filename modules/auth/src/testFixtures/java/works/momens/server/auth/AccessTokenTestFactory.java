package works.momens.server.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import works.momens.server.auth.internal.jwt.JwtTokenService;

/**
 * 보안 통합테스트용 access token 팩토리.
 *
 * <p>다른 모듈의 테스트가 {@code auth.internal}을 직접 참조하지 않도록, 토큰 발급/서명을 캡슐화해 auth 루트(public)에서 제공합니다. 실제 발급은
 * 컨텍스트의 내부 빈({@link JwtTokenService}, {@link JwtEncoder})에 위임하므로 운영과 같은 키로 서명됩니다.
 */
@Component
public class AccessTokenTestFactory {

  private final JwtTokenService jwtTokenService;
  private final JwtEncoder accessTokenEncoder;

  AccessTokenTestFactory(JwtTokenService jwtTokenService, JwtEncoder accessTokenEncoder) {
    this.jwtTokenService = jwtTokenService;
    this.accessTokenEncoder = accessTokenEncoder;
  }

  public String issueAccessToken(UUID userId) {
    return jwtTokenService.issueAccessToken(userId);
  }

  /** 서명은 올바르나 이미 만료된 access token. */
  public String issueExpiredAccessToken(UUID userId) {
    Instant expiredAt = Clock.systemUTC().instant().minusSeconds(3600);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(expiredAt.minusSeconds(60))
            .expiresAt(expiredAt)
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return accessTokenEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
