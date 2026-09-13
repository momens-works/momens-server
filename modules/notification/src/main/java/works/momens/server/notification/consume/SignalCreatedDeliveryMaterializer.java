package works.momens.server.notification.consume;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.notification.device.PushInstallationDirectory;
import works.momens.server.notification.dispatch.PushDispatcher;
import works.momens.server.outbox.OutboxEventReader;
import works.momens.server.outbox.OutboxEventView;
import works.momens.server.signal.SignalReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * {@code signal.created} outbox event를 기기별 pending delivery로 materialize하는 consumer
 * 트랜잭션(docs/design/signal-push-demo-design.md 10.1절, ADR-0009).
 *
 * <p>consumer 상태 행을 잠근 채 watermark 이후 event를 id 순서로 읽되 안전 지연(2초)을 지나지 않은 첫 event에서 멈추고, 소비 시점
 * workspace 전체 구성원의 활성 Android 설치로 pending delivery를 원자 생성한 뒤 watermark를 전진시킨다. 최초 기동 시 watermark도
 * 같은 안전 prefix의 끝으로 시드해 배포 이전의 과거 event를 소급 발송하지 않는다. 생산자 트랜잭션이 안전 지연 안에 종료된다는 전제에서만 committed
 * event 무손실을 보장하며, 더 오래 열린 트랜잭션은 보장 범위 밖이다. 운영 트랜잭션 특성이 바뀌면 안전 지연과 소비 방식을 함께 재검토한다.
 *
 * <p>Signal이 이미 없거나 활성 설치가 없는 구성원은 정상적으로 건너뛰며 event 실패로 처리하지 않는다(9절). hydrate 중 오류가 나면 트랜잭션이
 * rollback되어 watermark가 전진하지 않으므로 다음 폴링이 다시 시도한다(at-least-once).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SignalCreatedDeliveryMaterializer {

  static final String CONSUMER_NAME = "signal_created_push";
  static final String EVENT_SIGNAL_CREATED = "signal.created";
  static final Duration SAFETY_LAG = Duration.ofSeconds(2);
  private static final int BATCH_SIZE = 100;

  private final NotificationConsumerOffsetRepository offsetRepository;
  private final PushDispatcher pushDispatcher;
  private final PushInstallationDirectory pushInstallationDirectory;
  private final OutboxEventReader outboxEventReader;
  private final SignalReader signalReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  /** 한 폴링 구간을 materialize한다. */
  @Transactional
  public void materialize() {
    NotificationConsumerOffset offset = lockOrSeed();
    List<OutboxEventView> events =
        outboxEventReader.readAfter(offset.getLastOutboxId(), SAFETY_LAG, BATCH_SIZE);
    if (events.isEmpty()) {
      return;
    }
    for (OutboxEventView event : events) {
      if (EVENT_SIGNAL_CREATED.equals(event.eventType())) {
        materializeOne(event);
      }
    }
    offset.advanceTo(events.getLast().id());
  }

  private NotificationConsumerOffset lockOrSeed() {
    return offsetRepository
        .lockByName(CONSUMER_NAME)
        .orElseGet(
            () -> {
              offsetRepository.seedIgnoringConflict(
                  CONSUMER_NAME, outboxEventReader.latestIdBefore(SAFETY_LAG));
              return offsetRepository.lockByName(CONSUMER_NAME).orElseThrow();
            });
  }

  private boolean materializeOne(OutboxEventView event) {
    UUID signalId;
    try {
      signalId = UUID.fromString(event.aggregateId());
    } catch (IllegalArgumentException e) {
      log.warn(
          "signal.created aggregateId가 UUID가 아니라 건너뜀 outboxEventId={} aggregateId={}",
          event.id(),
          event.aggregateId());
      return false;
    }
    return signalReader
        .findLive(signalId)
        .map(signal -> materializeRecipients(event.id(), signal))
        .orElseGet(
            () -> {
              log.warn(
                  "signal.created의 Signal이 없어 건너뜀 outboxEventId={} signalId={}",
                  event.id(),
                  signalId);
              return false;
            });
  }

  private boolean materializeRecipients(long outboxEventId, SignalReader.Snapshot signal) {
    List<UUID> memberIds = workspaceMembershipReader.listMemberUserIds(signal.workspaceId());
    List<PushDispatcher.Recipient> recipients =
        pushInstallationDirectory.findActiveAndroid(memberIds).stream()
            .map(
                installation ->
                    new PushDispatcher.Recipient(installation.id(), installation.userId()))
            .toList();
    pushDispatcher.enqueue(outboxEventId, recipients);
    log.info(
        "signal.created delivery materialized outboxEventId={} signalId={} devices={}",
        outboxEventId,
        signal.id(),
        recipients.size());
    return !recipients.isEmpty();
  }
}
