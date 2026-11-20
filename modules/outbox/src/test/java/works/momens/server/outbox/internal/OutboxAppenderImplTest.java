package works.momens.server.outbox.internal;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxAppenderImplTest {

  @Mock private OutboxEventRepository outboxEventRepository;
  @InjectMocks private OutboxAppenderImpl outboxAppender;

  @Test
  void appendsWithIssuedByApiServerAndComposedIdempotencyKey() {
    UUID workspaceId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>();
    payload.put("origin_type", "signal");
    payload.put("origin_signal_id", UUID.randomUUID().toString());

    outboxAppender.append(workspaceId, "task", taskId.toString(), "task.created", payload);

    verify(outboxEventRepository)
        .insertIgnoringConflict(
            eq("api-server"),
            eq(workspaceId),
            eq("task"),
            eq(taskId.toString()),
            eq("task.created"),
            contains("\"origin_type\":\"signal\""),
            eq("task.created:" + taskId));
  }

  @Test
  void serializesExplicitNullFields() {
    UUID workspaceId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>();
    payload.put("origin_type", "manual");
    payload.put("origin_signal_id", null);

    outboxAppender.append(workspaceId, "task", taskId.toString(), "task.created", payload);

    verify(outboxEventRepository)
        .insertIgnoringConflict(
            eq("api-server"),
            eq(workspaceId),
            eq("task"),
            eq(taskId.toString()),
            eq("task.created"),
            contains("\"origin_signal_id\":null"),
            eq("task.created:" + taskId));
  }

  @Test
  @DisplayName("변경 건별 멱등키를 지정해 outbox 이벤트를 저장한다")
  void acceptsChangeSpecificIdempotencyKey() {
    UUID workspaceId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    String key = "task.updated:" + taskId + ":" + UUID.randomUUID();

    outboxAppender.appendWithIdempotencyKey(
        workspaceId, "task", taskId.toString(), "task.updated", Map.of(), key);

    verify(outboxEventRepository)
        .insertIgnoringConflict(
            "api-server", workspaceId, "task", taskId.toString(), "task.updated", "{}", key);
  }
}
