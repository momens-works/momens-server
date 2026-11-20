package works.momens.server.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.outbox.OutboxEventView;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxEventReaderImpl.class)
class OutboxEventReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

  @Autowired private OutboxEventReaderImpl reader;
  @Autowired private OutboxEventRepository repository;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("watermark 이후 event를 안전 지연을 지나지 않은 첫 event 전까지 id 오름차순으로 읽는다")
  void readsDuePrefixAfterWatermark() {
    Instant now = Instant.now();
    long oldEvent = insertEventCreatedAt("signal.created", "a", now.minus(10, ChronoUnit.SECONDS));
    long dueEvent = insertEventCreatedAt("signal.created", "b", now.minus(5, ChronoUnit.SECONDS));
    long freshEvent = insertEventCreatedAt("signal.created", "c", now);

    List<OutboxEventView> events = reader.readAfter(oldEvent, Duration.ofSeconds(2), 10);

    assertThat(events).extracting(OutboxEventView::id).containsExactly(dueEvent);
    assertThat(events.getFirst().workspaceId()).isEqualTo(WORKSPACE_ID);
    assertThat(events.getFirst().aggregateType()).isEqualTo("signal");
    assertThat(events.getFirst().eventType()).isEqualTo("signal.created");
    updateCreatedAt(freshEvent, now.minus(5, ChronoUnit.SECONDS));
    assertThat(reader.readAfter(0, Duration.ofSeconds(2), 10))
        .extracting(OutboxEventView::id)
        .containsExactly(oldEvent, dueEvent, freshEvent);
  }

  @Test
  @DisplayName("안전 지연을 지나지 않은 낮은 id는 오래된 높은 id가 watermark를 건너뛰지 못하게 막는다")
  void freshLowerIdCapsDueHigherId() {
    Instant now = Instant.now();
    long watermark =
        insertEventCreatedAt("signal.created", "prefix", now.minus(10, ChronoUnit.SECONDS));
    long freshLowerId = insertEventCreatedAt("signal.created", "fresh", now);
    long oldHigherId =
        insertEventCreatedAt("signal.created", "old-higher", now.minus(10, ChronoUnit.SECONDS));

    assertThat(reader.readAfter(watermark, Duration.ofSeconds(2), 10)).isEmpty();

    updateCreatedAt(freshLowerId, now.minus(5, ChronoUnit.SECONDS));

    assertThat(reader.readAfter(watermark, Duration.ofSeconds(2), 10))
        .extracting(OutboxEventView::id)
        .containsExactly(freshLowerId, oldHigherId);
  }

  @Test
  @DisplayName("최초 watermark 시드도 안전 지연 prefix만 전진하고, event가 없으면 0이다")
  void latestIdSeedsAtDuePrefix() {
    Instant now = Instant.now();
    assertThat(reader.latestIdBefore(Duration.ofSeconds(2))).isZero();

    long oldPrefix = insertEventCreatedAt("signal.created", "a", now.minus(10, ChronoUnit.SECONDS));
    long fresh = insertEventCreatedAt("signal.created", "b", now);
    long oldHigher = insertEventCreatedAt("signal.created", "c", now.minus(10, ChronoUnit.SECONDS));

    assertThat(reader.latestIdBefore(Duration.ofSeconds(2))).isEqualTo(oldPrefix);

    updateCreatedAt(fresh, now.minus(5, ChronoUnit.SECONDS));

    assertThat(reader.latestIdBefore(Duration.ofSeconds(2))).isEqualTo(oldHigher);
  }

  @Test
  @DisplayName("id 한 건 조회는 aggregateId를 포함한 view를 돌려준다")
  void findsById() {
    long id = insertEventCreatedAt("signal.created", "agg-1", Instant.now());

    assertThat(reader.findById(id))
        .hasValueSatisfying(event -> assertThat(event.aggregateId()).isEqualTo("agg-1"));
    assertThat(reader.findById(id + 999)).isEmpty();
  }

  private long insertEventCreatedAt(String eventType, String aggregateId, Instant createdAt) {
    repository.insertIgnoringConflict(
        "api-server",
        WORKSPACE_ID,
        "signal",
        aggregateId,
        eventType,
        "{}",
        eventType + ":" + aggregateId);
    long id =
        ((Number)
                entityManager
                    .createNativeQuery("SELECT id FROM outbox_events WHERE idempotency_key = :key")
                    .setParameter("key", eventType + ":" + aggregateId)
                    .getSingleResult())
            .longValue();
    updateCreatedAt(id, createdAt);
    return id;
  }

  private void updateCreatedAt(long id, Instant createdAt) {
    entityManager
        .createNativeQuery("UPDATE outbox_events SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", id)
        .executeUpdate();
  }
}
