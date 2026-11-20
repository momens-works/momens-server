package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static works.momens.server.minsu.draft.ledger.LedgerObservabilityFixture.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.DraftStatus;

/** 읽기 시점 deadline 투영을 실제 PostgreSQL로 검증한다. 소비자의 읽기 순서와 동시성은 app 통합 테스트에서 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class TaskDraftStatusReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("원장 행이 없는 task는 ready다")
  void reportsReadyWhenNoLedgerRowExists() {
    // 비동기 도입 이전에 만들어진 task, 비활성 상태의 convert, Signal을 거치지 않은 생성이 모두 여기다.
    assertThat(statusOf(UUID.randomUUID())).isEqualTo(DraftStatus.READY);
  }

  @Test
  @DisplayName("deadline 전의 pending은 generating이다")
  void reportsGeneratingWhilePendingBeforeDeadline() {
    UUID taskId = persist(Instant.now().plus(1, ChronoUnit.HOURS), null);

    assertThat(statusOf(taskId)).isEqualTo(DraftStatus.GENERATING);
  }

  @Test
  @DisplayName("종료된 원장은 사유와 무관하게 ready다")
  void reportsReadyWhenCompleted() {
    // 실패로 닫힌 작업도 ready다. 앱에 필요한 것은 "지금 보이는 값이 최종값인가"뿐이다.
    UUID taskId = persist(Instant.now().plus(1, ChronoUnit.HOURS), "retry_exhausted");

    assertThat(statusOf(taskId)).isEqualTo(DraftStatus.READY);
  }

  @Test
  @DisplayName("deadline을 넘긴 pending은 원장이 그대로여도 ready로 투영한다")
  void projectsReadyWhenDeadlinePassed() {
    // scheduler가 멈춰도, drain을 꺼도 앱이 generating에 무기한 갇히지 않는다는 보장이 이것이다.
    UUID taskId = persist(Instant.now().minus(1, ChronoUnit.MINUTES), null);

    assertThat(statusOf(taskId)).isEqualTo(DraftStatus.READY);
  }

  @Test
  @DisplayName("deadline으로 닫은 경우에만 투영 counter를 올린다")
  void countsOnlyDeadlineClosedProjections() {
    // 세 ready의 사유가 다르다. 앱에게는 같지만 운영에서는 이것만 0이 아니면 생성이 제때 끝나지 않았다는
    // 신호다. EXISTS 하나로는 이 구분이 나오지 않아 읽기 쿼리를 3분기로 넓혔다.
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerObservability observability = new MinsuLedgerObservability(meterRegistry);
    TaskDraftStatusReaderImpl reader = new TaskDraftStatusReaderImpl(repository, observability);

    reader.statusOf(UUID.randomUUID());
    reader.statusOf(persist(Instant.now().plus(1, ChronoUnit.HOURS), null));
    reader.statusOf(persist(Instant.now().plus(1, ChronoUnit.HOURS), "retry_exhausted"));
    reader.statusOf(persist(Instant.now().minus(1, ChronoUnit.MINUTES), null));

    assertThat(meterRegistry.get("momens.minsu.ledger.deadline.projections").counter().count())
        .isEqualTo(1);
  }

  private DraftStatus statusOf(UUID taskId) {
    return new TaskDraftStatusReaderImpl(repository, observability()).statusOf(taskId);
  }

  /** 종료 사유를 주면 completed로 닫는다. status와 사유의 정합은 DB 제약이 이미 강제한다. */
  private UUID persist(Instant readDeadlineAt, String completionReason) {
    UUID taskId = UUID.randomUUID();
    TaskDraftGeneration saved =
        repository.save(
            TaskDraftGeneration.builder()
                .workspaceId(UUID.randomUUID())
                .taskId(taskId)
                .signalTitle("결제 실패율이 올라감")
                .signalType("risk")
                .signalDescription("카드 결제 실패가 늘었다")
                .signalEvidence("[]")
                .baselineTitle("결제 실패율 대응")
                .baselineRole("pm")
                .baselinePriority("medium")
                .readDeadlineAt(readDeadlineAt)
                .applyCutoffAt(readDeadlineAt.minus(5, ChronoUnit.MINUTES))
                .nextAttemptAt(readDeadlineAt.minus(1, ChronoUnit.HOURS))
                .build());
    entityManager.flush();
    if (completionReason != null) {
      entityManager
          .getEntityManager()
          .createNativeQuery(
              "UPDATE minsu_task_draft_generations"
                  + " SET status = 'completed', completion_reason = :reason WHERE id = :id")
          .setParameter("reason", completionReason)
          .setParameter("id", saved.getId())
          .executeUpdate();
    }
    return taskId;
  }
}
