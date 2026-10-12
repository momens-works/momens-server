package works.momens.server.notification.fcm;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Firebase Admin SDK 기반 FCM adapter.
 *
 * <p>multicast 요청은 Admin SDK 한도에 맞춰 최대 500개 token 단위로 분할한다. token·Firebase 인증 정보·notification 본문
 * 원문은 오류 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "momens.notification.push.enabled", havingValue = "true")
class FirebaseFcmClient implements FcmClient {

  /** FCM multicast 1회 한도(Admin SDK sendEachForMulticast). */
  private static final int MULTICAST_LIMIT = 500;

  private final FirebaseMessaging firebaseMessaging;

  @Override
  public List<FcmSendResult> send(List<String> tokens, PushMessage message) {
    List<FcmSendResult> results = new ArrayList<>(tokens.size());
    for (int from = 0; from < tokens.size(); from += MULTICAST_LIMIT) {
      List<String> chunk = tokens.subList(from, Math.min(from + MULTICAST_LIMIT, tokens.size()));
      results.addAll(sendChunk(chunk, message));
    }
    return results;
  }

  private List<FcmSendResult> sendChunk(List<String> tokens, PushMessage message) {
    MulticastMessage multicast =
        MulticastMessage.builder()
            .addAllTokens(tokens)
            .setNotification(
                Notification.builder().setTitle(message.title()).setBody(message.body()).build())
            .putAllData(message.data())
            .build();
    try {
      List<SendResponse> responses =
          firebaseMessaging.sendEachForMulticast(multicast).getResponses();
      return responses.stream().map(FirebaseFcmClient::classify).toList();
    } catch (FirebaseMessagingException e) {
      // 호출 자체가 실패하면(네트워크·인증 등) 전 기기 일시 실패로 두고 백오프 재시도에 맡긴다.
      log.warn(
          "FCM multicast 호출 실패 devices={} errorCode={}", tokens.size(), e.getMessagingErrorCode());
      return Collections.nCopies(tokens.size(), FcmSendResult.TRANSIENT_FAILURE);
    }
  }

  private static FcmSendResult classify(SendResponse response) {
    if (response.isSuccessful()) {
      return FcmSendResult.SENT;
    }
    MessagingErrorCode errorCode =
        response.getException() == null ? null : response.getException().getMessagingErrorCode();
    // UNREGISTERED는 만료·삭제된 token, SENDER_ID_MISMATCH는 다른 Firebase 프로젝트의 token이다. 둘 다
    // token 자체가 무효라 재시도해도 회복되지 않으므로 설치를 비활성화한다. INVALID_ARGUMENT는 token뿐
    // 아니라 메시지 payload 오류로도 발생하므로 여기서 무효 token으로 단정하지 않는다. 서버 payload 버그로
    // 멀쩡한 기기를 무더기로 비활성화하는 오작동을 막기 위해 일시 실패로 두고 재시도 경로에 맡긴다.
    if (errorCode == MessagingErrorCode.UNREGISTERED
        || errorCode == MessagingErrorCode.SENDER_ID_MISMATCH) {
      return FcmSendResult.INVALID_TOKEN;
    }
    return FcmSendResult.TRANSIENT_FAILURE;
  }
}
