package works.momens.server.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * consume에 여는 발송 위임 계약을 실제 PostgreSQL로 검증합니다. 동일 event replay의 중복 발송 방지(복합 PK 멱등 기록)를 이 경계가 소유합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PushDispatcherImpl.class})
class PushDispatcherIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final long EVENT_ID = 7L;
  private static final UUID INSTALLATION_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Autowired private PushDispatcherImpl dispatcher;
  @Autowired private PushDeliveryRepository deliveryRepository;

  @MockitoBean private PushSender pushSender;

  @Test
  @DisplayName("enqueue는 수신 설치별 pending delivery를 기록한다")
  void enqueueCreatesPendingDeliveries() {
    dispatcher.enqueue(EVENT_ID, List.of(new PushDispatcher.Recipient(INSTALLATION_ID, USER_ID)));

    PushDelivery delivery =
        deliveryRepository.findById(new PushDeliveryId(EVENT_ID, INSTALLATION_ID)).orElseThrow();
    assertThat(delivery.isPending()).isTrue();
    assertThat(delivery.getTargetUserId()).isEqualTo(USER_ID);
    assertThat(delivery.getAttemptCount()).isZero();
  }

  @Test
  @DisplayName("같은 event·설치의 재기록은 무시되어 소비 replay가 중복 발송으로 이어지지 않는다")
  void enqueueIsIdempotentPerEventAndInstallation() {
    List<PushDispatcher.Recipient> recipients =
        List.of(new PushDispatcher.Recipient(INSTALLATION_ID, USER_ID));

    dispatcher.enqueue(EVENT_ID, recipients);
    dispatcher.enqueue(EVENT_ID, recipients);

    assertThat(deliveryRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("runSendPass는 발송기에 위임한다")
  void runSendPassDelegatesToSender() {
    dispatcher.runSendPass();

    verify(pushSender).runSendPass();
  }
}
