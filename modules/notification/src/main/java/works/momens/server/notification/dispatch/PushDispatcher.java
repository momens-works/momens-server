package works.momens.server.notification.dispatch;

import java.util.List;
import java.util.UUID;

/**
 * consume nested 모듈이 발송을 맡길 때 쓰는 계약.
 *
 * <p>기기별 발송 상태(aggregate {@code PushDelivery})와 재시도·실패 격리·FCM 호출은 전부 이 경계 뒤에 은닉된다. consumer는 "이
 * event를 이 설치들로 보낼 것"을 기록하고({@link #enqueue}), 발송 패스를 트리거할 뿐({@link #runSendPass}) 배달 방식은 알지 못한다.
 */
public interface PushDispatcher {

  /**
   * event의 수신 설치별 pending delivery를 기록한다. 동일 event·설치의 재기록은 무시되므로(복합 PK) 소비 replay가 중복 발송으로 이어지지
   * 않는다.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException 호출자(materialize) 트랜잭션
   *     없이 호출하면 실패한다. watermark 전진과 delivery 기록이 반드시 함께 commit되어야 하기 때문이다.
   */
  void enqueue(long outboxEventId, List<Recipient> recipients);

  /** 재시도 시각이 지난 pending delivery가 소진될 때까지 클레임·발송한다. 최초 전송과 재시도가 같은 경로를 지난다. */
  void runSendPass();

  /** 발송 대상 설치 한 건. */
  record Recipient(UUID installationId, UUID targetUserId) {}
}
