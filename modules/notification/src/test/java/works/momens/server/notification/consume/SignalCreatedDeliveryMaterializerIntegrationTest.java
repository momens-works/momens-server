package works.momens.server.notification.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.notification.device.PushInstallationDirectory;
import works.momens.server.notification.device.PushInstallationDirectory.InstallationSnapshot;
import works.momens.server.notification.dispatch.PushDispatcher;
import works.momens.server.outbox.OutboxEventReader;
import works.momens.server.outbox.OutboxEventView;
import works.momens.server.signal.SignalReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * signal.created 소비의 수신자 결정(발송 위임), watermark 전진·시드를 실제 PostgreSQL(offset 원장)로 검증합니다.
 * outbox·signal·workspace public API와 device·dispatch nested 모듈의 계약은 이 경계 밖 소유라 mock으로 둡니다. 동일
 * event replay의 중복 발송 방지는 dispatch(enqueue 멱등)가 소유하고 그쪽 테스트가 검증합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SignalCreatedDeliveryMaterializer.class})
class SignalCreatedDeliveryMaterializerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID SIGNAL_ID = UUID.randomUUID();
  private static final UUID MEMBER_A = UUID.randomUUID();
  private static final UUID MEMBER_B = UUID.randomUUID();
  private static final UUID INSTALLATION_A = UUID.randomUUID();
  private static final UUID INSTALLATION_B = UUID.randomUUID();
  private static final long EVENT_ID = 11L;

  @Autowired private SignalCreatedDeliveryMaterializer materializer;
  @Autowired private NotificationConsumerOffsetRepository offsetRepository;

  @MockitoBean private PushDispatcher pushDispatcher;
  @MockitoBean private PushInstallationDirectory pushInstallationDirectory;
  @MockitoBean private OutboxEventReader outboxEventReader;
  @MockitoBean private SignalReader signalReader;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @BeforeEach
  void stubDefaults() {
    when(outboxEventReader.readAfter(anyLong(), any(), anyInt())).thenReturn(List.of());
    when(outboxEventReader.latestIdBefore(any())).thenReturn(0L);
    when(pushInstallationDirectory.findActiveAndroid(anyCollection())).thenReturn(List.of());
  }

  @Test
  @DisplayName("workspace 구성원의 활성 android 설치를 발송에 위임하고 watermark를 전진시킨다")
  void enqueuesRecipientsAndAdvancesWatermark() {
    stubSignalCreatedEvent();
    when(pushInstallationDirectory.findActiveAndroid(List.of(MEMBER_A, MEMBER_B)))
        .thenReturn(
            List.of(
                new InstallationSnapshot(INSTALLATION_A, MEMBER_A, "token-a", true),
                new InstallationSnapshot(INSTALLATION_B, MEMBER_B, "token-b", true)));

    materializer.materialize();

    verify(pushDispatcher)
        .enqueue(
            EVENT_ID,
            List.of(
                new PushDispatcher.Recipient(INSTALLATION_A, MEMBER_A),
                new PushDispatcher.Recipient(INSTALLATION_B, MEMBER_B)));
    assertThat(offsetOf()).isEqualTo(EVENT_ID);
  }

  @Test
  @DisplayName("같은 event replay는 같은 수신자로 다시 위임한다(중복 방지는 dispatch 소유)")
  void replayDelegatesSameRecipientsAgain() {
    stubSignalCreatedEvent();
    when(pushInstallationDirectory.findActiveAndroid(List.of(MEMBER_A, MEMBER_B)))
        .thenReturn(List.of(new InstallationSnapshot(INSTALLATION_A, MEMBER_A, "token-a", true)));

    materializer.materialize();
    // 소비 트랜잭션이 commit 전에 종료된 상황(at-least-once replay)을 watermark 되감기로 재현한다.
    NotificationConsumerOffset offset =
        offsetRepository.findById(SignalCreatedDeliveryMaterializer.CONSUMER_NAME).orElseThrow();
    offset.advanceTo(0);
    offsetRepository.saveAndFlush(offset);
    materializer.materialize();

    verify(pushDispatcher, times(2))
        .enqueue(EVENT_ID, List.of(new PushDispatcher.Recipient(INSTALLATION_A, MEMBER_A)));
  }

  @Test
  @DisplayName("Signal이 이미 없는 event는 건너뛰고 watermark는 전진시킨다")
  void skipsMissingSignalButAdvancesWatermark() {
    when(outboxEventReader.readAfter(eq(0L), any(), anyInt()))
        .thenReturn(List.of(signalCreatedEvent()));
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.empty());

    materializer.materialize();

    verifyNoInteractions(pushDispatcher);
    assertThat(offsetOf()).isEqualTo(EVENT_ID);
  }

  @Test
  @DisplayName("signal.created가 아닌 event는 발송 위임 없이 watermark만 전진시킨다")
  void ignoresOtherEventTypes() {
    when(outboxEventReader.readAfter(eq(0L), any(), anyInt()))
        .thenReturn(
            List.of(
                new OutboxEventView(
                    EVENT_ID,
                    WORKSPACE_ID,
                    "signal",
                    SIGNAL_ID.toString(),
                    "signal.dismissed",
                    Instant.now())));

    materializer.materialize();
    verifyNoInteractions(pushDispatcher);
    assertThat(offsetOf()).isEqualTo(EVENT_ID);
  }

  @Test
  @DisplayName("최초 실행은 watermark를 현재 outbox 끝으로 시드해 과거 event를 소급 발송하지 않는다")
  void firstRunSeedsWatermarkAtCurrentTail() {
    when(outboxEventReader.latestIdBefore(any())).thenReturn(42L);
    when(outboxEventReader.readAfter(eq(42L), any(), anyInt())).thenReturn(List.of());

    materializer.materialize();

    assertThat(offsetOf()).isEqualTo(42L);
  }

  private void stubSignalCreatedEvent() {
    when(outboxEventReader.readAfter(eq(0L), any(), anyInt()))
        .thenReturn(List.of(signalCreatedEvent()));
    when(signalReader.findLive(SIGNAL_ID))
        .thenReturn(
            Optional.of(
                new SignalReader.Snapshot(
                    SIGNAL_ID,
                    WORKSPACE_ID,
                    PROJECT_ID,
                    "decision",
                    "결제 정책 결정 3일째 보류",
                    "설명",
                    "전체 영향")));
    when(workspaceMembershipReader.listMemberUserIds(WORKSPACE_ID))
        .thenReturn(List.of(MEMBER_A, MEMBER_B));
  }

  private static OutboxEventView signalCreatedEvent() {
    return new OutboxEventView(
        EVENT_ID, WORKSPACE_ID, "signal", SIGNAL_ID.toString(), "signal.created", Instant.now());
  }

  private long offsetOf() {
    return offsetRepository
        .findById(SignalCreatedDeliveryMaterializer.CONSUMER_NAME)
        .orElseThrow()
        .getLastOutboxId();
  }
}
