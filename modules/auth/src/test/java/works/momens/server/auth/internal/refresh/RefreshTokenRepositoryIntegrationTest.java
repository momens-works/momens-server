package works.momens.server.auth.internal.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class RefreshTokenRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savesAndFindsRefreshTokenByHash() {
    RefreshToken saved =
        refreshTokenRepository.save(
            RefreshToken.builder()
                .userId(UUID.randomUUID())
                .tokenHash("a".repeat(64))
                .clientType(ClientType.MOBILE)
                .device("iPhone")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    entityManager.flush();
    entityManager.clear();

    RefreshToken found = refreshTokenRepository.findByTokenHash(saved.getTokenHash()).orElseThrow();

    assertThat(found.getUserId()).isEqualTo(saved.getUserId());
    assertThat(found.getClientType()).isEqualTo(ClientType.MOBILE);
    assertThat(found.getDevice()).isEqualTo("iPhone");
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  void revokesActiveTokensInSessionScope() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    RefreshToken target =
        refreshTokenRepository.save(
            RefreshToken.builder()
                .userId(userId)
                .tokenHash("b".repeat(64))
                .clientType(ClientType.MOBILE)
                .device("iPhone")
                .expiresAt(now.plusSeconds(3600))
                .build());
    RefreshToken otherDevice =
        refreshTokenRepository.save(
            RefreshToken.builder()
                .userId(userId)
                .tokenHash("c".repeat(64))
                .clientType(ClientType.MOBILE)
                .device("iPad")
                .expiresAt(now.plusSeconds(3600))
                .build());
    entityManager.flush();

    refreshTokenRepository.revokeActiveBySessionScope(userId, ClientType.MOBILE, "iPhone", now);
    entityManager.flush();
    entityManager.clear();

    assertThat(refreshTokenRepository.findById(target.getId()).orElseThrow().getRevokedAt())
        .isNotNull();
    assertThat(refreshTokenRepository.findById(otherDevice.getId()).orElseThrow().getRevokedAt())
        .isNull();
  }
}
