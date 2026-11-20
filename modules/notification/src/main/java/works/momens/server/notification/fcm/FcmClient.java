package works.momens.server.notification.fcm;

import java.util.List;

/**
 * FCM 전송 adapter 경계. 발송기가 Firebase SDK 세부를 모른 채 token 목록과 메시지로 전송하고 token별 결과를 받는다.
 *
 * <p>구현은 예외를 던지지 않고 전송 실패를 결과로 분류해 돌려준다. 한 기기의 실패가 다른 기기의 발송을 막지 않아야 하기 때문이다.
 */
public interface FcmClient {

  /** tokens와 같은 순서로 token별 결과를 돌려준다. */
  List<FcmSendResult> send(List<String> tokens, PushMessage message);

  enum FcmSendResult {
    SENT,
    /** 무효·만료 token. 재시도하지 않고 설치를 비활성화한다. */
    INVALID_TOKEN,
    /** 일시 실패. 백오프 후 재시도한다. */
    TRANSIENT_FAILURE
  }
}
