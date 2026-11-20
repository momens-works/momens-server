package works.momens.server.outbox;

import java.util.Map;
import java.util.UUID;

/**
 * 도메인 write 트랜잭션에 append-only outbox row를 합류시키는 public API(ADR-0008).
 *
 * <p>이 서버가 발행하는 이벤트만 다루므로 {@code issued_by}는 구현이 {@code api-server}로 고정한다. 호출자는 자기 트랜잭션 안에서 호출해야
 * 하며(트랜잭션 없이 호출하면 실패한다), payload는 ID 중심의 작은 데이터로 둔다(worker가 필요 시 DB에서 hydrate, ADR-0008/ADR-0010).
 */
public interface OutboxAppender {

  /**
   * 일회성 이벤트의 멱등키는 {@code "{eventType}:{aggregateId}"}로 조립한다.
   *
   * @param eventType {@code "{aggregate}.{과거형 동사}"} 형태(ADR-0010), 예: {@code "task.created"}.
   * @param payload additive-only로만 진화하는 jsonb payload(ADR-0010). 값이 없는 필드도 명시적으로 {@code null}을 담아
   *     계약을 드러낸다.
   */
  void append(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload);

  /** 반복 가능한 변경은 변경 건마다 다른 키를 전달한다. 같은 outbox INSERT를 재시도할 때는 같은 키를 재사용한다. */
  void appendWithIdempotencyKey(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload,
      String idempotencyKey);
}
