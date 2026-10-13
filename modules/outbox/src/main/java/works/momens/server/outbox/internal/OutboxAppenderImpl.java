package works.momens.server.outbox.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.outbox.OutboxAppender;

/** payload 직렬화는 HTTP 응답의 날짜·프로퍼티 네이밍 설정에 영향받지 않도록 전용 {@link ObjectMapper}를 사용합니다. */
@Component
@RequiredArgsConstructor
class OutboxAppenderImpl implements OutboxAppender {

  private static final String ISSUED_BY = "api-server";

  private final OutboxEventRepository outboxEventRepository;
  // payload는 ID 중심의 작은 데이터라(ADR-0008) 시간 타입이 들어올 일이 없지만, 클래스패스에 있는 모듈
  // (JavaTimeModule 등)을 등록해 두어 누가 시간 값을 넣어도 조용히 실패하지 않게 한다.
  private final ObjectMapper outboxObjectMapper = new ObjectMapper().findAndRegisterModules();

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload) {
    appendWithIdempotencyKey(
        workspaceId, aggregateType, aggregateId, eventType, payload, eventType + ":" + aggregateId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void appendWithIdempotencyKey(
      UUID workspaceId,
      String aggregateType,
      String aggregateId,
      String eventType,
      Map<String, Object> payload,
      String idempotencyKey) {
    String serializedPayload = serialize(payload);
    outboxEventRepository.insertIgnoringConflict(
        ISSUED_BY,
        workspaceId,
        aggregateType,
        aggregateId,
        eventType,
        serializedPayload,
        idempotencyKey);
  }

  private String serialize(Map<String, Object> payload) {
    try {
      return outboxObjectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("outbox payload 직렬화 실패: " + payload, e);
    }
  }
}
