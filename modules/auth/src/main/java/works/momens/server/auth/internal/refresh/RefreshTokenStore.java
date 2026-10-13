package works.momens.server.auth.internal.refresh;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

  RefreshToken save(
      UUID userId, String tokenHash, ClientType clientType, String device, Instant expiresAt);

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  void revoke(RefreshToken refreshToken, Instant revokedAt);

  void revokeActiveBySessionScope(
      UUID userId, ClientType clientType, String device, Instant revokedAt);
}
