package works.momens.server.notification.dispatch;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
                outboxEventId, recipient.installationId(), recipient.targetUserId()));
  }

  @Override
  public void runSendPass() {
    pushSender.runSendPass();
  }
}
