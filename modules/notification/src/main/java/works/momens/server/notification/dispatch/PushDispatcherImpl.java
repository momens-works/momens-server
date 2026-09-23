package works.momens.server.notification.dispatch;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 발송 위임 계약 구현. 기록은 delivery 원장에, 발송은 발송기에 넘긴다. */
@Component
@RequiredArgsConstructor
class PushDispatcherImpl implements PushDispatcher {

  private final PushDeliveryRepository pushDeliveryRepository;
  private final PushSender pushSender;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(long outboxEventId, List<Recipient> recipients) {
    recipients.forEach(
        recipient ->
            pushDeliveryRepository.insertPendingIgnoringConflict(
                outboxEventId,
                recipient.installationId(),
                recipient.targetUserId(),
                DeliveryStatus.PENDING.value()));
  }

  @Override
  public void runSendPass() {
    pushSender.runSendPass();
  }
}
