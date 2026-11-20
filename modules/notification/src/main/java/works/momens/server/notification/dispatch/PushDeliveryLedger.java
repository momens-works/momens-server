package works.momens.server.notification.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.notification.device.PushInstallationDirectory;
import works.momens.server.notification.device.PushInstallationDirectory.InstallationSnapshot;
import works.momens.server.notification.fcm.FcmClient.FcmSendResult;

/**
 * delivery 클레임·결과 기록 트랜잭션.
 *
 * <p>클레임 트랜잭션 안에서 시도 횟수와 처리 lease를 먼저 commit하고, FCM 호출은 트랜잭션 밖({@link PushSender})에서 수행한다. 일시 실패
 * 결과를 기록할 때 lease를 실제 재시도 백오프로 교체한다. 서버가 전송 전에 종료되면 lease 만료 후 재시도되며 이는 at-least-once 특성 안에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PushDeliveryLedger {

  /** 최초 전송 후 일시 실패 백오프. 총 시도 횟수는 최초 전송을 포함해 최대 {@link PushDelivery#MAX_ATTEMPTS}회다. */
  private static final List<Duration> BACKOFFS =
      List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));

  private static final Duration PROCESSING_LEASE = Duration.ofSeconds(30);

  static final String CATEGORY_INVALID_TOKEN = "invalid_token";
  static final String CATEGORY_RETRY_EXHAUSTED = "retry_exhausted";
  static final String CATEGORY_INSTALLATION_MISMATCH = "installation_mismatch";

  private final PushDeliveryRepository pushDeliveryRepository;
  private final PushInstallationDirectory pushInstallationDirectory;

  /**
   * 재시도 시각이 지난 pending delivery를 클레임한다. 전송 직전 installation이 활성이고 소유 사용자가 대상 사용자와 같은지 다시 확인해, FID가
   * 다른 사용자에게 이전됐으면 cancelled 처리로 계정 간 오발송을 막는다.
   */
  @Transactional
  public List<ClaimedPushDelivery> claimDue(int limit) {
    List<PushDelivery> due = pushDeliveryRepository.lockDue(limit);
    if (due.isEmpty()) {
      return List.of();
    }
    Map<UUID, InstallationSnapshot> installations =
        pushInstallationDirectory
            .findByIds(due.stream().map(PushDelivery::getInstallationId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(InstallationSnapshot::id, Function.identity()));
    Instant leaseUntil = pushDeliveryRepository.currentDatabaseTime().plus(PROCESSING_LEASE);
    List<ClaimedPushDelivery> claimed = new ArrayList<>();
    for (PushDelivery delivery : due) {
      if (delivery.isExhausted()) {
        // 클레임 후 전송 결과를 기록하기 전에 종료된 잔여 행. 재시도 한도를 넘겼으므로 종결한다.
        delivery.markFailed(CATEGORY_RETRY_EXHAUSTED);
        continue;
      }
      InstallationSnapshot installation = installations.get(delivery.getInstallationId());
      if (installation == null
          || !installation.active()
          || !installation.userId().equals(delivery.getTargetUserId())) {
        delivery.cancel(CATEGORY_INSTALLATION_MISMATCH);
        continue;
      }
      UUID claimToken = UUID.randomUUID();
      delivery.claimAttempt(claimToken, leaseUntil);
      claimed.add(
          new ClaimedPushDelivery(
              delivery.getOutboxEventId(),
              delivery.getInstallationId(),
              installation.fcmRegistrationToken(),
              claimToken));
    }
    return claimed;
  }

  /** event 그룹 발송 직전에 아직 소유한 claim의 처리 lease를 갱신한다. 이미 재클레임된 delivery는 결과에서 제외한다. */
  @Transactional
  public List<ClaimedPushDelivery> renewLease(List<ClaimedPushDelivery> claims) {
    Instant leaseUntil = pushDeliveryRepository.currentDatabaseTime().plus(PROCESSING_LEASE);
    List<ClaimedPushDelivery> renewed = new ArrayList<>();
    for (ClaimedPushDelivery claim : claims) {
      PushDelivery delivery = lock(claim);
      if (!delivery.isClaimedBy(claim.claimToken())) {
        log.warn(
            "만료된 push claim lease 갱신 무시 outboxEventId={} installationId={}",
            claim.outboxEventId(),
            claim.installationId());
        continue;
      }
      delivery.renewLease(leaseUntil);
      renewed.add(claim);
    }
    return renewed;
  }

  /** token별 전송 결과를 기록한다. 무효 token은 재시도하지 않고 설치를 비활성화한다. */
  @Transactional
  public void record(List<ClaimedPushDelivery> claims, List<FcmSendResult> results) {
    Instant recordedAt = pushDeliveryRepository.currentDatabaseTime();
    for (int i = 0; i < claims.size(); i++) {
      ClaimedPushDelivery claim = claims.get(i);
      PushDelivery delivery = lock(claim);
      if (!delivery.isClaimedBy(claim.claimToken())) {
        log.warn(
            "만료된 push claim 결과 무시 outboxEventId={} installationId={}",
            claim.outboxEventId(),
            claim.installationId());
        continue;
      }
      switch (results.get(i)) {
        case SENT -> delivery.markSent();
        case INVALID_TOKEN -> {
          delivery.markFailed(CATEGORY_INVALID_TOKEN);
          pushInstallationDirectory.deactivateIfTokenMatches(
              claim.installationId(), claim.fcmRegistrationToken());
        }
        case TRANSIENT_FAILURE -> {
          if (delivery.isExhausted()) {
            delivery.markFailed(CATEGORY_RETRY_EXHAUSTED);
          } else {
            delivery.scheduleRetry(recordedAt.plus(retryBackoffFor(delivery.getAttemptCount())));
          }
          // 한도 전이면 pending을 유지하고 결과 기록 시점부터 계산한 백오프 뒤에 재시도한다.
        }
        default -> throw new IllegalStateException("unknown fcm send result");
      }
    }
  }

  /** event 본문(Signal·Project)을 더는 hydrate할 수 없는 delivery를 종결한다. */
  @Transactional
  public void cancelAll(List<ClaimedPushDelivery> claims, String failureCategory) {
    for (ClaimedPushDelivery claim : claims) {
      PushDelivery delivery = lock(claim);
      if (delivery.isClaimedBy(claim.claimToken())) {
        delivery.cancel(failureCategory);
      }
    }
  }

  private PushDelivery lock(ClaimedPushDelivery claim) {
    return pushDeliveryRepository
        .lockById(claim.outboxEventId(), claim.installationId())
        .orElseThrow();
  }

  private static Duration retryBackoffFor(int attemptCount) {
    int retryIndex = Math.min(Math.max(attemptCount - 1, 0), BACKOFFS.size() - 1);
    return BACKOFFS.get(retryIndex);
  }
}
