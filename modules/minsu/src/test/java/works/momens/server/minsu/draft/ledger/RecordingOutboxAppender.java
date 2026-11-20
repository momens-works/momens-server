package works.momens.server.minsu.draft.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.outbox.OutboxAppender;

/** append 호출을 기록하는 outbox 대역. 실제 row 저장과 트랜잭션 합류는 app 레벨 통합 테스트가 본다. */
class RecordingOutboxAppender implements OutboxAppender {

  record Appended(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload) {}

  private final List<Appended> appended = new ArrayList<>();

  @Override
  public void append(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload) {
    appended.add(new Appended(workspaceId, aggregateType, aggregateId, eventType, payload));
  }

  @Override
  public void appendWithIdempotencyKey(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload,
      String idempotencyKey) {
    append(workspaceId, aggregateType, aggregateId, eventType, payload);
  }

  List<Appended> appended() {
    return List.copyOf(appended);
  }

  void reset() {
    appended.clear();
  }
}
