package works.momens.server.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.notification.fcm.FcmClient;
import works.momens.server.notification.fcm.FcmClient.FcmSendResult;
import works.momens.server.notification.fcm.PushMessage;
import works.momens.server.outbox.OutboxEventReader;
import works.momens.server.outbox.OutboxEventView;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.core.ProjectSnapshot;
import works.momens.server.signal.SignalReader;

/** 발송 패스의 event 단위 hydrate, push 문구·data payload 계약, hydrate 실패 종결을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class PushSenderTest {

  private static final long EVENT_ID = 11L;
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID SIGNAL_ID = UUID.randomUUID();

  @Mock private PushDeliveryLedger ledger;
  @Mock private OutboxEventReader outboxEventReader;
  @Mock private SignalReader signalReader;
  @Mock private ProjectReader projectReader;
  @Mock private FcmClient fcmClient;
  @InjectMocks private PushSender pushSender;

  @Test
  @DisplayName("클레임이 없으면 아무것도 발송하지 않는다")
  void doesNothingWithoutClaims() {
    when(ledger.claimDue(anyInt())).thenReturn(List.of());

    pushSender.runSendPass();

    verifyNoInteractions(fcmClient);
  }

  @Test
  @DisplayName("event 단위로 Signal·Project를 hydrate해 계약된 문구·data payload로 발송하고 결과를 기록한다")
  void sendsHydratedMessageAndRecordsResults() {
    List<ClaimedPushDelivery> claims = List.of(claim("token-1"), claim("token-2"));
    when(ledger.claimDue(anyInt())).thenReturn(claims).thenReturn(List.of());
    when(ledger.renewLease(claims)).thenReturn(claims);
    stubHydration();
    when(fcmClient.send(anyList(), any()))
        .thenReturn(List.of(FcmSendResult.SENT, FcmSendResult.TRANSIENT_FAILURE));

    pushSender.runSendPass();

    ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
    verify(fcmClient).send(eq(List.of("token-1", "token-2")), message.capture());
    assertThat(message.getValue().title()).isEqualTo("Q2 Activation Readiness에 새 시그널이 발견되었습니다.");
    assertThat(message.getValue().body()).isEqualTo("결제 정책 결정 3일째 보류");
    assertThat(message.getValue().data())
        .containsEntry("notification_type", "signal_created")
        .containsEntry("destination", "signal_detail")
        .containsEntry("signal_id", SIGNAL_ID.toString())
        .containsEntry("project_id", PROJECT_ID.toString())
        .containsEntry("workspace_id", WORKSPACE_ID.toString());
    verify(ledger).record(claims, List.of(FcmSendResult.SENT, FcmSendResult.TRANSIENT_FAILURE));
  }

  @Test
  @DisplayName("발송 시점에 event 본문을 hydrate할 수 없으면 delivery를 종결하고 FCM을 호출하지 않는다")
  void cancelsWhenEventUnavailable() {
    List<ClaimedPushDelivery> claims = List.of(claim("token-1"));
    when(ledger.claimDue(anyInt())).thenReturn(claims).thenReturn(List.of());
    when(outboxEventReader.findById(EVENT_ID)).thenReturn(Optional.of(signalCreatedEvent()));
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.empty());

    pushSender.runSendPass();

    verify(ledger).cancelAll(claims, "event_unavailable");
    verify(fcmClient, never()).send(anyList(), any());
  }

  @Test
  @DisplayName("event 그룹이 발송 전에 lease를 잃었으면 FCM을 호출하지 않는다")
  void skipsClaimsWhoseLeaseWasLost() {
    List<ClaimedPushDelivery> claims = List.of(claim("token-1"));
    when(ledger.claimDue(anyInt())).thenReturn(claims).thenReturn(List.of());
    when(ledger.renewLease(claims)).thenReturn(List.of());
    stubHydration();

    pushSender.runSendPass();

    verify(fcmClient, never()).send(anyList(), any());
    verify(ledger, never()).record(anyList(), anyList());
  }

  private void stubHydration() {
    when(outboxEventReader.findById(EVENT_ID)).thenReturn(Optional.of(signalCreatedEvent()));
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
    when(projectReader.findSnapshot(PROJECT_ID))
        .thenReturn(
            Optional.of(
                new ProjectSnapshot(
                    PROJECT_ID, WORKSPACE_ID, "Q2 Activation Readiness", null, null)));
  }

  private static OutboxEventView signalCreatedEvent() {
    return new OutboxEventView(
        EVENT_ID, WORKSPACE_ID, "signal", SIGNAL_ID.toString(), "signal.created", Instant.now());
  }

  private static ClaimedPushDelivery claim(String token) {
    return new ClaimedPushDelivery(EVENT_ID, UUID.randomUUID(), token, UUID.randomUUID());
  }
}
