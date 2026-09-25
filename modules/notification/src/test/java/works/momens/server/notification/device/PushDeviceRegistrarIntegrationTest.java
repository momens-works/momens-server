package works.momens.server.notification.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.notification.PushInstallationPlatform;

/**
 * 설치 등록·해제의 소유권 이전, token 이동, 멱등 해제와 활성 token 부분 unique 제약을 실제 PostgreSQL로
 * 검증합니다(docs/design/signal-push-demo-design.md 8절).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PushDeviceRegistrarImpl.class})
class PushDeviceRegistrarIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID OTHER_USER_ID = UUID.randomUUID();
  private static final String FID = "fid-1";
  private static final String OTHER_FID = "fid-2";
  private static final String TOKEN = "token-1";
  private static final String OTHER_TOKEN = "token-2";
  private static final String ANDROID = "android";

  @Autowired private PushDeviceRegistrarImpl registrar;
  @Autowired private PushInstallationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("신규 등록은 활성 설치를 만든다")
  void registerCreatesActiveInstallation() {
    registrar.register(USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);
    entityManager.flush();
    entityManager.clear();

    PushInstallation installation = repository.findByFirebaseInstallationId(FID).orElseThrow();
    assertThat(installation.getUserId()).isEqualTo(USER_ID);
    assertThat(installation.getFcmRegistrationToken()).isEqualTo(TOKEN);
    assertThat(installation.getPlatform()).isEqualTo(ANDROID);
    assertThat(installation.isActive()).isTrue();
    assertThat(installation.getDeactivatedAt()).isNull();
  }

  @Test
  @DisplayName("같은 FID 재등록은 token을 갱신하고 다시 활성화한다")
  void reRegisterRefreshesTokenAndReactivates() {
    registrar.register(USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);
    registrar.unregister(USER_ID, FID);

    registrar.register(USER_ID, FID, OTHER_TOKEN, PushInstallationPlatform.ANDROID);
    entityManager.flush();
    entityManager.clear();

    PushInstallation installation = repository.findByFirebaseInstallationId(FID).orElseThrow();
    assertThat(installation.getFcmRegistrationToken()).isEqualTo(OTHER_TOKEN);
    assertThat(installation.isActive()).isTrue();
    assertThat(installation.getDeactivatedAt()).isNull();
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 사용자에게 귀속된 FID 등록은 현재 사용자에게 소유권을 이전한다")
  void registerTransfersOwnership() {
    registrar.register(OTHER_USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);

    registrar.register(USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);
    entityManager.flush();
    entityManager.clear();

    PushInstallation installation = repository.findByFirebaseInstallationId(FID).orElseThrow();
    assertThat(installation.getUserId()).isEqualTo(USER_ID);
    assertThat(installation.isActive()).isTrue();
  }

  @Test
  @DisplayName("같은 활성 token이 다른 FID에 연결돼 있으면 이전 연결을 비활성화한다")
  void registerDeactivatesPreviousTokenHolder() {
    registrar.register(USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);

    registrar.register(USER_ID, OTHER_FID, TOKEN, PushInstallationPlatform.ANDROID);
    entityManager.flush();
    entityManager.clear();

    PushInstallation previous = repository.findByFirebaseInstallationId(FID).orElseThrow();
    PushInstallation current = repository.findByFirebaseInstallationId(OTHER_FID).orElseThrow();
    assertThat(previous.isActive()).isFalse();
    assertThat(previous.getDeactivatedAt()).isNotNull();
    assertThat(current.isActive()).isTrue();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("같은 token을 서로 다른 FID가 동시에 등록해도 한 활성 설치로 직렬화한다")
  void concurrentRegistrationSerializesActiveTokenTransfer() throws Exception {
    String token = "concurrent-token-" + UUID.randomUUID();
    String firstFid = "concurrent-fid-a-" + UUID.randomUUID();
    String secondFid = "concurrent-fid-b-" + UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(
              () -> {
                awaitStart(ready, start);
                registrar.register(USER_ID, firstFid, token, PushInstallationPlatform.ANDROID);
              });
      Future<?> second =
          executor.submit(
              () -> {
                awaitStart(ready, start);
                registrar.register(
                    OTHER_USER_ID, secondFid, token, PushInstallationPlatform.ANDROID);
              });

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);

      assertThat(repository.findByFcmRegistrationTokenAndActiveTrue(token)).hasSize(1);
      assertThat(repository.findByFirebaseInstallationId(firstFid)).isPresent();
      assertThat(repository.findByFirebaseInstallationId(secondFid)).isPresent();
    } finally {
      repository.findByFirebaseInstallationId(firstFid).ifPresent(repository::delete);
      repository.findByFirebaseInstallationId(secondFid).ifPresent(repository::delete);
      repository.flush();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("FID·token 공백은 검증 실패로 거부한다")
  void registerRejectsBlankValues() {
    assertThatThrownBy(
            () -> registrar.register(USER_ID, " ", TOKEN, PushInstallationPlatform.ANDROID))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> registrar.register(USER_ID, FID, " ", PushInstallationPlatform.ANDROID))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("해제는 자기 소유 활성 설치만 비활성화하고, 없는·비활성 설치는 멱등 처리한다")
  void unregisterDeactivatesOwnInstallationIdempotently() {
    registrar.register(USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);

    registrar.unregister(USER_ID, "unknown-fid");
    registrar.unregister(USER_ID, FID);
    registrar.unregister(USER_ID, FID);
    entityManager.flush();
    entityManager.clear();

    PushInstallation installation = repository.findByFirebaseInstallationId(FID).orElseThrow();
    assertThat(installation.isActive()).isFalse();
    assertThat(installation.getDeactivatedAt()).isNotNull();
  }

  @Test
  @DisplayName("다른 사용자가 소유한 활성 설치는 해제하지 않는다")
  void unregisterIgnoresOtherUsersInstallation() {
    registrar.register(OTHER_USER_ID, FID, TOKEN, PushInstallationPlatform.ANDROID);

    registrar.unregister(USER_ID, FID);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findByFirebaseInstallationId(FID).orElseThrow().isActive()).isTrue();
  }

  @Test
  @DisplayName("활성 행의 같은 token 중복은 부분 unique 제약이 거부한다")
  void schemaRejectsDuplicateActiveToken() {
    repository.saveAndFlush(
        PushInstallation.builder()
            .firebaseInstallationId(FID)
            .userId(USER_ID)
            .fcmRegistrationToken(TOKEN)
            .platform(ANDROID)
            .build());

    assertThatThrownBy(
            () ->
                repository.saveAndFlush(
                    PushInstallation.builder()
                        .firebaseInstallationId(OTHER_FID)
                        .userId(OTHER_USER_ID)
                        .fcmRegistrationToken(TOKEN)
                        .platform(ANDROID)
                        .build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("동시 등록 시작 대기 시간 초과");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시 등록 대기 중 중단", e);
    }
  }
}
