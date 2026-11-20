package works.momens.server.source.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SourceConnectionResyncIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired private SourceConnectionRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("기존 요청 시각이 미래여도 수락한 요청마다 식별 값이 증가한다")
  void everyAcceptedRequestAdvancesEvenWhenStoredTimeIsAhead() {
    UUID workspaceId = UUID.randomUUID();
    UUID id = connection(workspaceId);
    Instant previous = Instant.parse("2200-01-01T00:00:00Z");
    Instant workerUpdate = previous.plusSeconds(60);
    jdbc.update(
        "UPDATE source_connections SET resync_requested_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(previous),
        Timestamp.from(workerUpdate),
        id);
    for (int i = 1; i <= 3; i++) {
      assertThat(repository.requestResync(id, workspaceId)).isEqualTo(1);
      assertThat(time(id, "resync_requested_at")).isEqualTo(previous.plusNanos(i * 1000L));
      assertThat(time(id, "updated_at")).isEqualTo(workerUpdate);
    }
  }

  @Test
  @DisplayName("첫 요청은 DB 시각을 사용하고 연속 요청도 구별한다")
  void initialRequestUsesDatabaseTimeAndRepeatedRequestsAreDistinct() {
    UUID workspaceId = UUID.randomUUID();
    UUID id = connection(workspaceId);
    Instant before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    assertThat(repository.requestResync(id, workspaceId)).isEqualTo(1);
    Instant first = time(id, "resync_requested_at");
    Instant after = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    assertThat(first).isBetween(before, after);
    assertThat(repository.requestResync(id, workspaceId)).isEqualTo(1);
    assertThat(time(id, "resync_requested_at")).isAfter(first);
    assertThat(time(id, "updated_at")).isEqualTo(time(id, "resync_requested_at"));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("동시 요청도 각각 증가한 식별 값을 커밋한다")
  void concurrentRequestsEachCommitADistinctBoundary() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID id = connection(workspaceId);
    Instant previous = Instant.parse("2200-01-01T00:00:00Z");
    jdbc.update(
        "UPDATE source_connections SET resync_requested_at = ? WHERE id = ?",
        Timestamp.from(previous),
        id);
    int requests = 8;
    CountDownLatch ready = new CountDownLatch(requests);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(requests);
    try {
      List<Future<Instant>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        futures.add(
            executor.submit(() -> {
              ready.countDown();
              if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("request barrier timed out");
              }
              return new TransactionTemplate(transactionManager)
                  .execute(status -> {
                    assertThat(repository.requestResync(id, workspaceId)).isEqualTo(1);
                    return time(id, "resync_requested_at");
                  });
            }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Instant> boundaries = new ArrayList<>();
      for (Future<Instant> future : futures) {
        boundaries.add(future.get(10, TimeUnit.SECONDS));
      }
      assertThat(boundaries).doesNotHaveDuplicates();
      assertThat(time(id, "resync_requested_at")).isEqualTo(previous.plusNanos(requests * 1000L));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
    }
  }

  private UUID connection(UUID workspaceId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'resync', ?)",
        workspaceId,
        workspaceId.toString());
    jdbc.update(
        "INSERT INTO source_connections (id, workspace_id, source_type, status, updated_at)"
            + " VALUES (?, ?, 'GITHUB', 'ACTIVE', '2026-01-01T00:00:00Z')",
        id,
        workspaceId);
    return id;
  }

  private Instant time(UUID id, String column) {
    return jdbc.queryForObject(
            "SELECT " + column + " FROM source_connections WHERE id = ?", Timestamp.class, id)
        .toInstant();
  }
}
