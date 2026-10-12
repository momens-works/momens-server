package works.momens.server.notification.consume;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import works.momens.server.notification.dispatch.PushDispatcher;

/**
 * signal.created 소비·발송 스케줄링.
 *
 * <p>1초 주기로 outbox를 polling해 delivery를 materialize하고, commit 직후 같은 인스턴스가 발송 패스를 즉시 실행해 지연을 줄인다. 발송
 * 패스는 materialization이 없어도 매 주기 실행해 백오프가 지난 재시도와 재시작 후 남은 pending을 회수한다. 스케줄링 인프라는 app이 항상 켜고, push
 * 비활성 환경(local·test 기본)에서는 이 빈이 등록되지 않는 방식으로 폴링이 멈춘다.
 */
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
