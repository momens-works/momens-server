package works.momens.server.notification.dispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import works.momens.server.notification.fcm.FcmClient;
import works.momens.server.notification.fcm.PushMessage;
import works.momens.server.outbox.OutboxEventReader;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalReader;

/**
 * 클레임된 delivery를 FCM으로 발송하는 발송기.
 *
 * <p>클레임(DB 트랜잭션)과 FCM 호출(트랜잭션 밖)을 분리하고, event 단위로 저장된 Signal·Project를 hydrate해 push 문구와 data
 * payload를 만든다. 서버는 프로젝트명과 Signal 제목을 임의로 자르지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PushSender {

  private static final int CLAIM_BATCH_SIZE = 100;
  private static final int MAX_ROUNDS_PER_PASS = 10;
  private static final String CATEGORY_EVENT_UNAVAILABLE = "event_unavailable";

  private final PushDeliveryLedger pushDeliveryLedger;
  private final OutboxEventReader outboxEventReader;
  private final SignalReader signalReader;
  private final ProjectReader projectReader;
  private final FcmClient fcmClient;

  public void runSendPass() {
    for (int round = 0; round < MAX_ROUNDS_PER_PASS; round++) {
      List<ClaimedPushDelivery> claims = pushDeliveryLedger.claimDue(CLAIM_BATCH_SIZE);
      if (claims.isEmpty()) {
        return;
      }
      claims.stream()
          .collect(
              Collectors.groupingBy(
                  ClaimedPushDelivery::outboxEventId, LinkedHashMap::new, Collectors.toList()))
          .forEach(this::sendEventGroup);
    }
  }

  private void sendEventGroup(long outboxEventId, List<ClaimedPushDelivery> claims) {
    Optional<PushMessage> message = buildMessage(outboxEventId);
    if (message.isEmpty()) {
      log.warn(
          "발송 시점에 event 본문을 hydrate할 수 없어 delivery를 종결 outboxEventId={} devices={}",
          outboxEventId,
          claims.size());
      pushDeliveryLedger.cancelAll(claims, CATEGORY_EVENT_UNAVAILABLE);
      return;
    }
    List<ClaimedPushDelivery> renewedClaims = pushDeliveryLedger.renewLease(claims);
    if (renewedClaims.isEmpty()) {
      return;
    }
    List<String> tokens =
        renewedClaims.stream().map(ClaimedPushDelivery::fcmRegistrationToken).toList();
    List<FcmClient.FcmSendResult> results = fcmClient.send(tokens, message.get());
    pushDeliveryLedger.record(renewedClaims, results);
    log.info(
        "signal push 발송 시도 outboxEventId={} devices={} sent={}",
        outboxEventId,
        renewedClaims.size(),
        results.stream().filter(FcmClient.FcmSendResult.SENT::equals).count());
  }

  private Optional<PushMessage> buildMessage(long outboxEventId) {
    return outboxEventReader
        .findById(outboxEventId)
        .flatMap(event -> signalReader.findLive(UUID.fromString(event.aggregateId())))
        .flatMap(
            signal ->
                projectReader
                    .findSnapshot(signal.projectId())
                    .map(
                        project ->
                            new PushMessage(
                                project.name() + "에 새 시그널이 발견되었습니다.",
                                signal.title(),
                                Map.of(
                                    "notification_type", "signal_created",
                                    "destination", "signal_detail",
                                    "signal_id", signal.id().toString(),
                                    "project_id", signal.projectId().toString(),
                                    "workspace_id", signal.workspaceId().toString()))));
  }
}
