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

/**
 * api-server가 발행하는 outbox row를 호출자 트랜잭션에 합류시킨다(ADR-0008). {@code issued_by}는 이 서버가 발행하는 이벤트만 다루므로
 * {@code "api-server"}로 고정한다.
 *
 * <p>{@code payload} 직렬화는 web 계층 {@code ObjectMapper} 설정(날짜 포맷, 프로퍼티 네이밍 등)과 절연하기 위해 전용 {@link
 * ObjectMapper}를 쓴다. 멱등키는 {@code "{event_type}:{aggregate_id}"}로 결정적으로 조립한다(SD-3).
 */
@Component
@RequiredArgsConstructor
class OutboxAppenderImpl implements OutboxAppender {

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
    String serializedPayload = serialize(payload);
    String idempotencyKey = eventType + ":" + aggregateId;
    outboxEventRepository.insertIgnoringConflict(
        OutboxEventIssuer.API_SERVER.value(),
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
