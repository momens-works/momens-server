package works.momens.server.auth.internal.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.auth.internal.config.AuthProperties;
import works.momens.server.auth.internal.refresh.ClientType;
import works.momens.server.auth.internal.refresh.RefreshToken;
import works.momens.server.auth.internal.refresh.RefreshTokenStore;
import works.momens.server.common.api.BusinessException;

/** 토큰은 사용자 식별자만 담고 권한 정보는 담지 않습니다. 권한은 요청 처리 시 workspace API로 확인합니다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JwtTokenService {

  private static final int REFRESH_TOKEN_BYTES = 32;
  private static final int MAX_DEVICE_LENGTH = 255;

  private final JwtEncoder accessTokenEncoder;
  private final AuthProperties properties;
  private final Clock clock;
  private final RefreshTokenStore refreshTokenStore;
  private final SecureRandom secureRandom = new SecureRandom();

  public String issueAccessToken(UUID userId) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl()))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return accessTokenEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  @Transactional
  public TokenPair issueTokenPair(UUID userId, ClientType clientType, String device) {
    String refreshToken = generateRefreshToken();
    Instant now = clock.instant();
    refreshTokenStore.save(
        userId,
        hashRefreshToken(refreshToken),
        clientType,
        normalizeDevice(device),
        now.plus(properties.refreshTtl()));
    return new TokenPair(
        issueAccessToken(userId), refreshToken, properties.accessTtl().toSeconds());
  }

  @Transactional
  public TokenPair refresh(String refreshToken) {
    Instant now = clock.instant();
    RefreshToken current = findRefreshToken(refreshToken);

    if (!current.isActive(now)) {
      if (current.getRevokedAt() != null) {
        refreshTokenStore.revokeActiveBySessionScope(
            current.getUserId(), current.getClientType(), current.getDevice(), now);
      }
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    refreshTokenStore.revoke(current, now);
    return issueTokenPair(current.getUserId(), current.getClientType(), current.getDevice());
  }

  @Transactional
  public void revoke(String refreshToken) {
    Instant now = clock.instant();
    findRefreshTokenIfPresent(refreshToken)
        .filter(current -> current.isActive(now))
        .ifPresent(current -> refreshTokenStore.revoke(current, now));
  }

  private RefreshToken findRefreshToken(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }
    return refreshTokenStore
        .findByTokenHash(hashRefreshToken(refreshToken))
        .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));
  }

  private Optional<RefreshToken> findRefreshTokenIfPresent(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return Optional.empty();
    }
    return refreshTokenStore.findByTokenHash(hashRefreshToken(refreshToken));
  }

  private String generateRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hashRefreshToken(String refreshToken) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is not available", e);
    }
  }

  private static String normalizeDevice(String device) {
    if (device == null || device.isBlank()) {
      return null;
    }
    String normalized = device.strip();
    if (normalized.codePointCount(0, normalized.length()) <= MAX_DEVICE_LENGTH) {
      return normalized;
    }
    int endIndex = normalized.offsetByCodePoints(0, MAX_DEVICE_LENGTH);
    return normalized.substring(0, endIndex);
  }
}
