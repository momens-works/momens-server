package works.momens.server.notification.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FcmTestResponses;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.notification.fcm.FcmClient.FcmSendResult;

/** token별 결과 분류(무효 token vs 일시 실패)와 500개 단위 multicast 분할을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class FirebaseFcmClientTest {

  private static final PushMessage MESSAGE = new PushMessage("제목", "본문", Map.of("k", "v"));

  @Mock private FirebaseMessaging firebaseMessaging;

  @Test
  @DisplayName("성공·무효 token·일시 실패를 token 순서대로 분류한다")
  void classifiesResponsesInOrder() throws Exception {
    FirebaseFcmClient client = new FirebaseFcmClient(firebaseMessaging);
    BatchResponse batch = mock(BatchResponse.class);
    when(batch.getResponses())
        .thenReturn(
            List.of(
                FcmTestResponses.success(),
                FcmTestResponses.failure(MessagingErrorCode.UNREGISTERED),
                FcmTestResponses.failure(MessagingErrorCode.UNAVAILABLE)));
    when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

    List<FcmSendResult> results = client.send(List.of("t1", "t2", "t3"), MESSAGE);

    assertThat(results)
        .containsExactly(
            FcmSendResult.SENT, FcmSendResult.INVALID_TOKEN, FcmSendResult.TRANSIENT_FAILURE);
  }

  @Test
  @DisplayName("INVALID_ARGUMENT는 무효 token으로 단정하지 않고 일시 실패로 둔다")
  void invalidArgumentIsTransientNotInvalidToken() throws Exception {
    // INVALID_ARGUMENT는 메시지 payload 오류로도 발생하므로, 서버 payload 버그로 멀쩡한 기기가
    // 비활성화되지 않도록 무효 token(설치 비활성화)이 아니라 일시 실패로 분류해야 한다.
    FirebaseFcmClient client = new FirebaseFcmClient(firebaseMessaging);
    BatchResponse batch = mock(BatchResponse.class);
    when(batch.getResponses())
        .thenReturn(List.of(FcmTestResponses.failure(MessagingErrorCode.INVALID_ARGUMENT)));
    when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

    List<FcmSendResult> results = client.send(List.of("t1"), MESSAGE);

    assertThat(results).containsExactly(FcmSendResult.TRANSIENT_FAILURE);
  }

  @Test
  @DisplayName("500개를 넘는 token은 multicast 한도 단위로 분할 전송한다")
  void chunksTokensAtMulticastLimit() throws Exception {
    FirebaseFcmClient client = new FirebaseFcmClient(firebaseMessaging);
    BatchResponse firstChunk = batchOfSuccess(500);
    BatchResponse secondChunk = batchOfSuccess(100);
    when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
        .thenReturn(firstChunk)
        .thenReturn(secondChunk);

    List<String> tokens = IntStream.range(0, 600).mapToObj(i -> "token-" + i).toList();
    List<FcmSendResult> results = client.send(tokens, MESSAGE);

    assertThat(results).hasSize(600).allMatch(FcmSendResult.SENT::equals);
    verify(firebaseMessaging, times(2)).sendEachForMulticast(any(MulticastMessage.class));
  }

  @Test
  @DisplayName("multicast 호출 자체가 실패하면 전 기기 일시 실패로 분류한다")
  void wholeCallFailureIsTransientForAllTokens() throws Exception {
    FirebaseFcmClient client = new FirebaseFcmClient(firebaseMessaging);
    when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
        .thenThrow(FcmTestResponses.messagingException(MessagingErrorCode.INTERNAL));

    List<FcmSendResult> results = client.send(List.of("t1", "t2"), MESSAGE);

    assertThat(results)
        .containsExactly(FcmSendResult.TRANSIENT_FAILURE, FcmSendResult.TRANSIENT_FAILURE);
  }

  private static BatchResponse batchOfSuccess(int size) {
    BatchResponse batch = mock(BatchResponse.class);
    when(batch.getResponses()).thenReturn(Collections.nCopies(size, FcmTestResponses.success()));
    return batch;
  }
}
