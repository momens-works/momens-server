package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.generation.AsyncGenerationResult;
import works.momens.server.minsu.draft.generation.AsyncTaskDraftExecutor;
import works.momens.server.minsu.draft.generation.GenerationOutcome;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/**
 * 포화 뒤 재시작 복구를 <b>하나의 시나리오</b>로 검증한다(MOM-0819 완료 조건, 설계 8.5·9.1절).
 *
 * <p>{@code AsyncTaskDraftExecutorTest}는 슬롯이 묶이는 것까지, {@code
 * TaskDraftGenerationLedgerIntegrationTest}는 lease 만료 회수까지 각각 본다. 둘을 따로 보면 <b>정작 중요한 연결</b>이 빠진다.
 * interrupt를 무시하는 호출이 붙들고 있던 실제 원장 행이 재시작 뒤 scheduler를 통해 끝까지 처리되는지다. 그 연결이 끊어져도 두 테스트는 모두 통과한다.
 *
 * <p>실행기는 mock으로 둔다. 여기서 보려는 것은 원장과 scheduler의 복구 경로이고, 멈춘 호출이 스레드를 놓지 않는다는 성질 자체는 {@code
 * AsyncTaskDraftExecutorTest}가 진짜 스레드로 확인한다. 여기서 재현하는 것은 그 성질이 원장에 남기는 <b>흔적</b>이다. claim은 커밋됐는데 결과
 * 기록이 없는 행과, 그동안 0으로 떨어진 빈 슬롯.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfig.class,
  MinsuJson.class,
  TaskDraftGenerationLedger.class,
  LedgerObservabilityFixture.class,
  MinsuDrainRestartRecoveryIntegrationTest.RecoveryTestConfig.class
})
class MinsuDrainRestartRecoveryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private TaskDraftGenerationLedger ledger;
  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private EntityManager entityManager;

  private final MinsuConfigStatus configStatus = mock(MinsuConfigStatus.class);
  private final AsyncTaskDraftExecutor executor = mock(AsyncTaskDraftExecutor.class);
  private MinsuDrainScheduler scheduler;

  @TestConfiguration
  static class RecoveryTestConfig {

    @Bean
    MinsuAsyncProperties minsuAsyncProperties() {
      return new MinsuAsyncProperties(
          true,
          true,
          Duration.ofMinutes(10),
          Duration.ofMinutes(1),
          new MinsuAsyncProperties.Execution(
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              Duration.ofSeconds(5),
              3,
              List.of(Duration.ofSeconds(1)),
              1));
    }

    // 이 테스트가 보는 것은 원장과 scheduler의 복구 경로다. 반영과 발행은 항상 성공한 것으로 둔다.
    @Bean
    FakeTaskDraftApplier taskDraftApplier() {
      return new FakeTaskDraftApplier();
    }

    @Bean
    RecordingOutboxAppender outboxAppender() {
      return new RecordingOutboxAppender();
    }
  }

  @BeforeEach
  void wireScheduler() {
    when(configStatus.enabled()).thenReturn(true);
    when(configStatus.valid()).thenReturn(true);
    scheduler = new MinsuDrainScheduler(configStatus, ledger, executor, new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("멈춘 호출이 슬롯을 모두 점유해도 재시작 후 그 작업이 끝까지 처리된다")
  void recoversHungAttemptAfterRestart() {
    UUID taskId = persistPending();

    // 1) 실행 중 프로세스가 죽는다. claim은 커밋됐지만 결과를 기록할 기회가 없었다.
    when(executor.availableSlots()).thenReturn(1);
    doThrow(new IllegalStateException("프로세스 종료")).when(executor).executeAll(any());
    scheduler.drain();

    TaskDraftGeneration afterCrash = reload(taskId);
    assertAll(
        () -> assertThat(afterCrash.getStatus()).isEqualTo("processing"),
        () -> assertThat(afterCrash.getClaimToken()).isNotNull(),
        () -> assertThat(afterCrash.getAttemptCount()).isEqualTo(1));

    // 2) 멈춘 호출이 슬롯을 놓지 않는 동안에는 아무것도 집지 않는다. 여기서 집으면 실행되지 못한
    //    작업이 시도 횟수만 소모한다(9.1절).
    when(executor.availableSlots()).thenReturn(0);
    scheduler.drain();

    assertThat(reload(taskId).getAttemptCount()).isEqualTo(1);

    // 3) Pod 재시작. 슬롯이 회복되고, 그 사이 lease가 만료된다. 원장이 durable하므로 새 프로세스가
    //    그대로 이어받는다.
    when(executor.availableSlots()).thenReturn(1);
    // when()은 mock을 실제로 호출하므로 이전 스텁의 예외가 재스텁 시점에 터진다. doReturn을 쓴다.
    doReturn(List.of(new AsyncGenerationResult(draft(), GenerationOutcome.GENERATED)))
        .when(executor)
        .executeAll(any());
    expireLease();
    scheduler.drain();

    TaskDraftGeneration recovered = reload(taskId);
    assertAll(
        () -> assertThat(recovered.getStatus()).isEqualTo("completed"),
        () -> assertThat(recovered.getCompletionReason()).isEqualTo("generated"),
        // 회수도 하나의 시도다. 세지 않으면 lease 만료가 반복될 때 상한에 도달하지 못한다.
        () -> assertThat(recovered.getAttemptCount()).isEqualTo(2),
        () -> assertThat(recovered.getClaimToken()).isNull(),
        () -> assertThat(recovered.getLeaseExpiresAt()).isNull());
  }

  private static TaskDraft draft() {
    return new TaskDraft("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.HIGH);
  }

  private TaskDraftGeneration reload(UUID taskId) {
    entityManager.flush();
    entityManager.clear();
    return repository.findByTaskId(taskId).orElseThrow();
  }

  private void expireLease() {
    entityManager
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations"
                + " SET lease_expires_at = NOW() - INTERVAL '1 minute' WHERE status = 'processing'")
        .executeUpdate();
    entityManager.clear();
  }

  private UUID persistPending() {
    UUID taskId = UUID.randomUUID();
    // PostgreSQL의 NOW()는 트랜잭션 시작 시각이라 적재 기준도 DB 시계로 맞춘다.
    Instant enrolledAt = repository.currentDatabaseTime();
    Instant readDeadline = enrolledAt.plus(10, ChronoUnit.MINUTES);
    repository.save(
        TaskDraftGeneration.builder()
            .workspaceId(UUID.randomUUID())
            .taskId(taskId)
            .signalTitle("결제 실패율이 올라감")
            .signalType("risk")
            .signalDescription("카드 결제 실패가 늘었다")
            .signalEvidence("[]")
            .baselineTitle("결제 실패율 대응")
            .baselineRole("backend")
            .baselinePriority("medium")
            .readDeadlineAt(readDeadline)
            .applyCutoffAt(readDeadline.minus(1, ChronoUnit.MINUTES))
            .nextAttemptAt(enrolledAt)
            .build());
    entityManager.flush();
    entityManager.clear();
    return taskId;
  }
}
