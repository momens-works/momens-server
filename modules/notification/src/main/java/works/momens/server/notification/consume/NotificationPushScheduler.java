package works.momens.server.notification.consume;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import works.momens.server.notification.dispatch.PushDispatcher;

/** materialization이 없어도 매 주기 발송 패스를 실행해 재시도와 재시작 후 남은 pending을 회수합니다. push 비활성 시 이 빈은 등록되지 않습니다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "momens.notification.push.enabled", havingValue = "true")
class NotificationPushScheduler {

  private final SignalCreatedDeliveryMaterializer materializer;
  private final PushDispatcher pushDispatcher;

  @Scheduled(fixedDelayString = "1s")
  void poll() {
    try {
      materializer.materialize();
      pushDispatcher.runSendPass();
    } catch (RuntimeException e) {
      // watermark가 전진하지 않았으므로 다음 주기가 같은 구간을 다시 시도한다(at-least-once).
      log.error("signal push 폴링 실패", e);
    }
  }
}
