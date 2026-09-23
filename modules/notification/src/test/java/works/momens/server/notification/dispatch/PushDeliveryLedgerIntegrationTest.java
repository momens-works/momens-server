package works.momens.server.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.notification.device.PushInstallationDirectory;
import works.momens.server.notification.device.PushInstallationDirectory.InstallationSnapshot;
import works.momens.server.notification.fcm.FcmClient.FcmSendResult;

/**
 * delivery 클레임(백오프 선반영·소유자 재확인)과 결과 기록(sent·invalid token·재시도 한도)을 실제 PostgreSQL로
 * 검증합니다(docs/design/signal-push-demo-design.md 10.2·10.3절). 설치 원장은 device nested 모듈 소유라 계약 ({@link
 * PushInstallationDirectory}) mock으로 둡니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PushDeliveryLedger.class})
class PushDeliveryLedgerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final long EVENT_ID = 7L;
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID INSTALLATION_ID = UUID.randomUUID();

  @Autowired private PushDeliveryLedger ledger;
  @Autowired private PushDeliveryRepository deliveryRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private PushInstallationDirectory pushInstallationDirectory;

  @BeforeEach
  void stubActiveInstallation() {
    when(pushInstallationDirectory.findByIds(anyCollection()))
        .thenReturn(List.of(new InstallationSnapshot(INSTALLATION_ID, USER_ID, "token-1", true)));
  }

  @Test
  @DisplayName("클레임은 시도 횟수와 처리 lease를 선반영하고 현재 token을 돌려준다")
  void claimAdvancesAttemptAndLease() {
    materializePending();
    Instant databaseNow = deliveryRepository.currentDatabaseTime();

    List<ClaimedPushDelivery> claims = ledger.claimDue(10);

    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().fcmRegistrationToken()).isEqualTo("token-1");
    assertThat(claims.getFirst().claimToken()).isNotNull();
    PushDelivery delivery = reload();
    assertThat(delivery.getAttemptCount()).isEqualTo(1);
    assertThat(delivery.isPending()).isTrue();
    assertThat(delivery.getNextAttemptAt()).isEqualTo(databaseNow.plus(Duration.ofSeconds(30)));
    assertThat(delivery.getClaimToken()).isEqualTo(claims.getFirst().claimToken());
    // 처리 lease가 선반영돼 즉시 재클레임되지 않는다.
    assertThat(ledger.claimDue(10)).isEmpty();
  }

  @Test
  @DisplayName("일시 실패 결과는 처리 lease를 첫 재시도 백오프 1초로 교체한다")
  void transientFailureSchedulesRetryFromResult() {
    materializePending();
    Instant databaseNow = deliveryRepository.currentDatabaseTime();
    List<ClaimedPushDelivery> claims = ledger.claimDue(10);

    ledger.record(claims, List.of(FcmSendResult.TRANSIENT_FAILURE));

    PushDelivery delivery = reload();
    assertThat(delivery.isPending()).isTrue();
    assertThat(delivery.getNextAttemptAt()).isEqualTo(databaseNow.plus(Duration.ofSeconds(1)));
  }

  @Test
  @DisplayName("설치가 비활성이거나 다른 사용자에게 이전됐으면 cancelled 처리하고 클레임하지 않는다")
  void claimCancelsMismatchedInstallation() {
    materializePending();
    when(pushInstallationDirectory.findByIds(anyCollection()))
        .thenReturn(
            List.of(new InstallationSnapshot(INSTALLATION_ID, UUID.randomUUID(), "token-1", true)));

    assertThat(ledger.claimDue(10)).isEmpty();
    PushDelivery delivery = reload();
    assertThat(delivery.getStatus()).isEqualTo("cancelled");
    assertThat(delivery.getFailureCategory())
        .isEqualTo(PushDeliveryLedger.CATEGORY_INSTALLATION_MISMATCH);
  }

  @Test
  @DisplayName("전송 성공 기록은 sent와 sent_at을 남긴다")
  void recordSentMarksDelivery() {
    materializePending();
    List<ClaimedPushDelivery> claims = ledger.claimDue(10);

    ledger.record(claims, List.of(FcmSendResult.SENT));

    PushDelivery delivery = reload();
    assertThat(delivery.getStatus()).isEqualTo("sent");
    assertThat(delivery.getSentAt()).isNotNull();
  }

  @Test
  @DisplayName("무효 token은 재시도 없이 failed 처리하고 설치를 비활성화한다")
  void recordInvalidTokenDeactivatesInstallation() {
    materializePending();
    List<ClaimedPushDelivery> claims = ledger.claimDue(10);

    ledger.record(claims, List.of(FcmSendResult.INVALID_TOKEN));

    PushDelivery delivery = reload();
    assertThat(delivery.getStatus()).isEqualTo("failed");
    assertThat(delivery.getFailureCategory()).isEqualTo(PushDeliveryLedger.CATEGORY_INVALID_TOKEN);
    verify(pushInstallationDirectory).deactivateIfTokenMatches(INSTALLATION_ID, "token-1");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("lease 만료 후 재클레임되면 이전 claim의 결과를 무시한다")
  void staleClaimCannotOverwriteNewClaim() {
    try {
      inTransaction(this::materializePending);
      List<ClaimedPushDelivery> staleClaims = ledger.claimDue(10);
      inTransaction(this::backdateNextAttempt);
      List<ClaimedPushDelivery> currentClaims = ledger.claimDue(10);

      assertThat(currentClaims).hasSize(1);
      assertThat(currentClaims.getFirst().claimToken())
          .isNotEqualTo(staleClaims.getFirst().claimToken());

      ledger.record(staleClaims, List.of(FcmSendResult.INVALID_TOKEN));

      PushDelivery afterStaleResult =
          deliveryRepository.findById(new PushDeliveryId(EVENT_ID, INSTALLATION_ID)).orElseThrow();
      assertThat(afterStaleResult.isPending()).isTrue();
      assertThat(afterStaleResult.getAttemptCount()).isEqualTo(2);
      assertThat(afterStaleResult.getClaimToken()).isEqualTo(currentClaims.getFirst().claimToken());
      verify(pushInstallationDirectory, never())
          .deactivateIfTokenMatches(INSTALLATION_ID, "token-1");

      ledger.record(currentClaims, List.of(FcmSendResult.SENT));
      assertThat(
              deliveryRepository
                  .findById(new PushDeliveryId(EVENT_ID, INSTALLATION_ID))
                  .orElseThrow()
                  .getStatus())
          .isEqualTo("sent");
    } finally {
      inTransaction(
          () -> deliveryRepository.deleteById(new PushDeliveryId(EVENT_ID, INSTALLATION_ID)));
    }
  }

  @Test
  @DisplayName("event 그룹 발송 직전 lease를 갱신하면 즉시 재클레임되지 않는다")
  void renewsLeaseBeforeSendingEventGroup() {
    materializePending();
    List<ClaimedPushDelivery> claims = ledger.claimDue(10);
    backdateNextAttempt();
    Instant databaseNow = deliveryRepository.currentDatabaseTime();

    assertThat(ledger.renewLease(claims)).containsExactlyElementsOf(claims);

    PushDelivery delivery = reload();
    assertThat(delivery.getNextAttemptAt()).isEqualTo(databaseNow.plus(Duration.ofSeconds(30)));
    assertThat(ledger.claimDue(10)).isEmpty();
  }

  @Test
  @DisplayName("일시 실패는 pending을 유지하고 최대 4회 시도 후 failed로 종결한다")
  void transientFailureRetriesUntilExhausted() {
    materializePending();

    for (int attempt = 1; attempt <= 4; attempt++) {
      backdateNextAttempt();
      List<ClaimedPushDelivery> claims = ledger.claimDue(10);
      assertThat(claims).hasSize(1);
      ledger.record(claims, List.of(FcmSendResult.TRANSIENT_FAILURE));
      entityManager.flush();
      entityManager.clear();

      PushDelivery delivery = reload();
      assertThat(delivery.getAttemptCount()).isEqualTo(attempt);
      if (attempt < 4) {
        assertThat(delivery.isPending()).isTrue();
      } else {
        assertThat(delivery.getStatus()).isEqualTo("failed");
        assertThat(delivery.getFailureCategory())
            .isEqualTo(PushDeliveryLedger.CATEGORY_RETRY_EXHAUSTED);
      }
    }
  }

  @Test
  @DisplayName("클레임 후 결과 기록 전에 종료된 한도 초과 행은 다음 클레임이 failed로 종결한다")
  void claimFinalizesExhaustedLeftover() {
    materializePending();
    entityManager
        .createNativeQuery(
            "UPDATE push_deliveries SET attempt_count = 4, next_attempt_at = NOW() - interval '1 minute'")
        .executeUpdate();
    entityManager.clear();

    assertThat(ledger.claimDue(10)).isEmpty();
    PushDelivery delivery = reload();
    assertThat(delivery.getStatus()).isEqualTo("failed");
    assertThat(delivery.getFailureCategory())
        .isEqualTo(PushDeliveryLedger.CATEGORY_RETRY_EXHAUSTED);
  }

  @Test
  @DisplayName("event 본문을 hydrate할 수 없는 delivery는 cancelled로 종결한다")
  void cancelAllMarksCancelled() {
    materializePending();
    List<ClaimedPushDelivery> claims = ledger.claimDue(10);

    ledger.cancelAll(claims, "event_unavailable");

    assertThat(reload().getStatus()).isEqualTo("cancelled");
  }

  private void materializePending() {
    deliveryRepository.insertPendingIgnoringConflict(
        EVENT_ID, INSTALLATION_ID, USER_ID, DeliveryStatus.PENDING.value());
    entityManager.clear();
  }

  private void backdateNextAttempt() {
    entityManager
        .createNativeQuery(
            "UPDATE push_deliveries SET next_attempt_at = NOW() - interval '1 minute'")
        .executeUpdate();
    entityManager.clear();
  }

  private PushDelivery reload() {
    entityManager.flush();
    entityManager.clear();
    return deliveryRepository.findById(new PushDeliveryId(EVENT_ID, INSTALLATION_ID)).orElseThrow();
  }

  private void inTransaction(Runnable action) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(transactionStatus -> action.run());
  }
}
