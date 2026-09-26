package works.momens.server.notification.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.notification.PushInstallationPlatform;
import works.momens.server.notification.device.PushInstallationDirectory.InstallationSnapshot;

/** delivery nested 모듈에 여는 설치 원장 계약(활성 android 조회·id 조회·무효 token 비활성화)을 실제 PostgreSQL로 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfig.class,
  PushInstallationDirectoryImpl.class,
  PushDeviceRegistrarImpl.class
})
class PushInstallationDirectoryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID USER_A = UUID.randomUUID();
  private static final UUID USER_B = UUID.randomUUID();
  private static final UUID OTHER_USER = UUID.randomUUID();

  @Autowired private PushInstallationDirectoryImpl directory;
  @Autowired private PushDeviceRegistrarImpl registrar;
  @Autowired private PushInstallationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("주어진 사용자들의 활성 설치만 조회한다")
  void findsActiveAndroidInstallationsOfUsers() {
    PushInstallation active = saveInstallation("fid-a", USER_A, "token-a", true);
    saveInstallation("fid-a2", USER_A, "token-a2", false);
    saveInstallation("fid-other", OTHER_USER, "token-other", true);

    List<InstallationSnapshot> snapshots = directory.findActiveAndroid(List.of(USER_A, USER_B));

    assertThat(snapshots)
        .containsExactly(new InstallationSnapshot(active.getId(), USER_A, "token-a", true));
    assertThat(directory.findActiveAndroid(List.of())).isEmpty();
  }

  @Test
  @DisplayName("id 조회는 비활성 설치도 현재 상태 그대로 돌려준다")
  void findsByIdsIncludingInactive() {
    PushInstallation inactive = saveInstallation("fid-a", USER_A, "token-a", false);

    List<InstallationSnapshot> snapshots = directory.findByIds(List.of(inactive.getId()));

    assertThat(snapshots)
        .containsExactly(new InstallationSnapshot(inactive.getId(), USER_A, "token-a", false));
  }

  @Test
  @DisplayName("token refresh 후 도착한 이전 token 실패는 갱신된 설치를 비활성화하지 않는다")
  void staleTokenFailureDoesNotDeactivateRefreshedInstallation() {
    registrar.register(USER_A, "fid-a", "token-a", PushInstallationPlatform.ANDROID);
    PushInstallation installation = repository.findByFirebaseInstallationId("fid-a").orElseThrow();
    registrar.register(USER_A, "fid-a", "token-b", PushInstallationPlatform.ANDROID);

    directory.deactivateIfTokenMatches(installation.getId(), "token-a");
    entityManager.clear();

    PushInstallation refreshed = repository.findById(installation.getId()).orElseThrow();
    assertThat(refreshed.getFcmRegistrationToken()).isEqualTo("token-b");
    assertThat(refreshed.isActive()).isTrue();

    directory.deactivateIfTokenMatches(installation.getId(), "token-b");
    directory.deactivateIfTokenMatches(UUID.randomUUID(), "token-b");
    entityManager.clear();

    PushInstallation reloaded = repository.findById(installation.getId()).orElseThrow();
    assertThat(reloaded.isActive()).isFalse();
    assertThat(reloaded.getDeactivatedAt()).isNotNull();
  }

  private PushInstallation saveInstallation(String fid, UUID userId, String token, boolean active) {
    PushInstallation installation =
        PushInstallation.builder()
            .firebaseInstallationId(fid)
            .userId(userId)
            .fcmRegistrationToken(token)
            .platform("android")
            .build();
    if (!active) {
      installation.deactivate();
    }
    return repository.saveAndFlush(installation);
  }
}
