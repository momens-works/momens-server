package works.momens.server.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxEventRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("저장한 값을 그대로 조회한다")
  void insertsAndReadsBack() {
    UUID taskId = UUID.randomUUID();
    outboxEventRepository.insertIgnoringConflict(
        "api-server",
        WORKSPACE_ID,
        "task",
        taskId.toString(),
        "task.created",
        "{\"origin_type\":\"manual\",\"origin_signal_id\":null}",
        "task.created:" + taskId);
    entityManager.flush();
    entityManager.clear();

    OutboxEvent saved = outboxEventRepository.findAll().get(0);

    assertThat(saved.getIssuedBy()).isEqualTo("api-server");
    assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
    assertThat(saved.getAggregateType()).isEqualTo("task");
    assertThat(saved.getAggregateId()).isEqualTo(taskId.toString());
    assertThat(saved.getEventType()).isEqualTo("task.created");
    // Postgres jsonb는 저장 시 텍스트 표현을 정규화한다(콜론 뒤 공백 등)라 정확한 포맷 대신 값 포함만 확인한다.
    assertThat(saved.getPayload()).contains("origin_type").contains("manual");
    assertThat(saved.getIdempotencyKey()).isEqualTo("task.created:" + taskId);
  }

  @Test
  @DisplayName("같은 idempotency_key 재삽입은 조용히 무시되고 행이 늘지 않는다")
  void ignoresDuplicateIdempotencyKey() {
    UUID signalId = UUID.randomUUID();
    String idempotencyKey = "signal.dismissed:" + signalId;
    outboxEventRepository.insertIgnoringConflict(
        "api-server",
        WORKSPACE_ID,
        "signal",
        signalId.toString(),
        "signal.dismissed",
        "{}",
        idempotencyKey);
    entityManager.flush();

    outboxEventRepository.insertIgnoringConflict(
        "api-server",
        WORKSPACE_ID,
        "signal",
        signalId.toString(),
        "signal.dismissed",
        "{}",
        idempotencyKey);
    entityManager.flush();
    entityManager.clear();

    assertThat(outboxEventRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("같은 태스크의 서로 다른 변경 멱등키는 각각 저장한다")
  void storesSeparateChangesToSameAggregate() {
    UUID taskId = UUID.randomUUID();
    String key = "task.updated:" + taskId + ":";
    for (int change = 1; change <= 2; change++) {
      outboxEventRepository.insertIgnoringConflict(
          "api-server",
          WORKSPACE_ID,
          "task",
          taskId.toString(),
          "task.updated",
          "{}",
          key + change);
    }
    entityManager.flush();
    entityManager.clear();

    assertThat(outboxEventRepository.findAll())
        .extracting(OutboxEvent::getIdempotencyKey)
        .containsExactlyInAnyOrder(key + 1, key + 2);
  }
}
