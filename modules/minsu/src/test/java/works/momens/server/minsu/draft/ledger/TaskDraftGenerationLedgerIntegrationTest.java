package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.generation.AsyncGenerationResult;
import works.momens.server.minsu.draft.generation.GenerationOutcome;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.project.task.ApplyTaskDraftCommand;
import works.momens.server.project.task.TaskDraftApplyResult;
import works.momens.server.project.task.TaskDraftValues;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/**
 * claim·재시도·결과 기록을 실제 PostgreSQL로 검증한다(MOM-0819, 설계 7.1·8.2·8.5절).
 *
 * <p>{@code notification}의 {@code PushDeliveryLedgerIntegrationTest}와 같은 형태다. 다른 점은 minsu가 lease와
 * {@code next_attempt_at}을 별도 컬럼으로 갖는다는 것이라(7.1절), lease 만료 회수와 백오프 대기가 각각 다른 컬럼을 되돌려 재현된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfig.class,
  MinsuJson.class,
  TaskDraftGenerationLedger.class,
  LedgerObservabilityFixture.class,
  TaskDraftGenerationLedgerIntegrationTest.LedgerTestConfig.class
})
class TaskDraftGenerationLedgerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final int MAX_ATTEMPTS = 3;
  private static final Duration LEASE = Duration.ofSeconds(5);
  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

  @Autowired private TaskDraftGenerationLedger ledger;
  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private FakeTaskDraftApplier applier;
  @Autowired private RecordingOutboxAppender outbox;

  @TestConfiguration
  static class LedgerTestConfig {

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
              LEASE,
              MAX_ATTEMPTS,
              List.of(FIRST_BACKOFF, Duration.ofSeconds(2)),
              2));
    }

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
  void resetDoubles() {
    // 컨텍스트를 공유하므로 대역 상태는 테스트마다 되돌린다.
    applier.reset();
    outbox.reset();
  }

  @Test
  @DisplayName("claim은 소유권과 lease를 기록하고 즉시 재claim되지 않는다")
  void claimRecordsOwnershipAndLease() {
    UUID taskId = persistPending();
    Instant databaseNow = repository.currentDatabaseTime();

    List<ClaimedGeneration> claims = ledger.claimDue(10);

    assertThat(claims).hasSize(1);
    TaskDraftGeneration generation = reload(taskId);
    assertAll(
        () -> assertThat(claims.getFirst().taskId()).isEqualTo(taskId),
        () -> assertThat(generation.getStatus()).isEqualTo("processing"),
        () -> assertThat(generation.getAttemptCount()).isEqualTo(1),
        () -> assertThat(generation.getClaimToken()).isEqualTo(claims.getFirst().claimToken()),
        () -> assertThat(generation.getLeaseExpiresAt()).isEqualTo(databaseNow.plus(LEASE)),
        // lease가 살아 있는 동안에는 다른 주기가 같은 행을 집지 않는다.
        () -> assertThat(ledger.claimDue(10)).isEmpty());
  }

  @Test
  @DisplayName("claim은 convert 시점 snapshot을 그대로 넘긴다")
  void claimCarriesEnrolledSnapshot() {
    persistPending();

    SignalTaskDraftInput input = ledger.claimDue(10).getFirst().input();

    // 실행 시점에 Signal을 다시 읽지 않으므로(5.6절) 원장에 적재된 값이 그대로 프롬프트 재료가 된다.
    assertAll(
        () -> assertThat(input.title()).isEqualTo("결제 실패율이 올라감"),
        () -> assertThat(input.type()).isEqualTo("risk"),
        () -> assertThat(input.description()).isEqualTo("카드 결제 실패가 늘었다"),
        () -> assertThat(input.impact()).isEqualTo("결제 전환율 하락"),
        () -> assertThat(input.evidence()).hasSize(1),
        () -> assertThat(input.evidence().getFirst().target()).isEqualTo("결제 화면"));
  }

  @Test
  @DisplayName("lease가 만료된 processing을 회수해 다시 claim한다")
  void reclaimsExpiredLease() {
    UUID taskId = persistPending();
    ledger.claimDue(10);
    expireLease(taskId);

    List<ClaimedGeneration> reclaimed = ledger.claimDue(10);

    assertThat(reclaimed).hasSize(1);
    TaskDraftGeneration generation = reload(taskId);
    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("processing"),
        // 회수도 하나의 시도다. 세지 않으면 lease 만료가 반복될 때 상한에 도달하지 못한다.
        () -> assertThat(generation.getAttemptCount()).isEqualTo(2),
        () -> assertThat(generation.getClaimToken()).isEqualTo(reclaimed.getFirst().claimToken()));
  }

  @Test
  @DisplayName("신규 pending이 슬롯을 모두 채워도 만료된 lease가 회수된다")
  void expiredLeaseIsReclaimedEvenWhenPendingFillsEverySlot() {
    UUID stalled = persistPending();
    ledger.claimDue(1);
    expireLease(stalled);
    // 회수를 기다리는 동안에도 사용자 convert는 계속 들어온다. 신규를 먼저 보면 매 주기 pending이
    // 슬롯을 모두 채우는 사이 만료 행이 한 번도 집히지 않고, 그대로 apply_cutoff_at을 지나면 두
    // 쿼리 모두에서 빠져 영영 회수되지 않는다(8.5절의 복구 보장이 부하에 따라 깨진다).
    persistPending();
    persistPending();

    List<ClaimedGeneration> claims = ledger.claimDue(1);

    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().taskId()).isEqualTo(stalled);
    assertThat(reload(stalled).getAttemptCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("만료된 lease가 없으면 남은 자리를 전부 pending으로 채운다")
  void fillsRemainingSlotsWithPendingWhenNothingToReclaim() {
    persistPending();
    persistPending();

    // 회수를 먼저 본다고 해서 평시 처리량이 줄지는 않는다.
    assertThat(ledger.claimDue(2)).hasSize(2);
  }

  @Test
  @DisplayName("재시작 후 남은 pending을 다음 주기가 그대로 이어받는다")
  void resumesPendingAfterRestart() {
    UUID taskId = persistPending();
    // 프로세스가 claim 전에 죽으면 원장은 pending 그대로다. 재시작 후 상태를 잃지 않는지 본다.
    entityManager.clear();

    List<ClaimedGeneration> claims = ledger.claimDue(10);

    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().taskId()).isEqualTo(taskId);
    assertThat(reload(taskId).getAttemptCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("성공 결과는 원장이 보유한 baseline으로 반영을 요청한다")
  void successAppliesDraftAgainstStoredBaseline() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();

    ledger.record(claim, result(GenerationOutcome.GENERATED));

    // 반영 규칙을 다시 계산하지 않고 적재 시점에 복사한 값을 그대로 쓴다(6절). 재계산하면 fallback 규칙이
    // 바뀌는 순간 진행 중이던 작업 전부가 user_edited로 오분류된다.
    ApplyTaskDraftCommand command = applier.commands().getFirst();
    assertAll(
        () -> assertThat(applier.commands()).hasSize(1),
        () -> assertThat(command.taskId()).isEqualTo(taskId),
        () ->
            assertThat(command.baseline())
                .isEqualTo(new TaskDraftValues("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.MEDIUM)),
        () ->
            assertThat(command.draft())
                .isEqualTo(new TaskDraftValues("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.HIGH)));
  }

  @Test
  @DisplayName("반영에 성공하면 generated로 닫고 task.draft_generated를 한 번 발행한다")
  void appliedResultCompletesAsGeneratedAndPublishesEvent() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();

    ledger.record(claim, result(GenerationOutcome.GENERATED));

    TaskDraftGeneration generation = reload(taskId);
    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("completed"),
        () -> assertThat(generation.getCompletionReason()).isEqualTo("generated"),
        () -> assertThat(generation.getClaimToken()).isNull(),
        () -> assertThat(outbox.appended()).hasSize(1),
        () ->
            assertThat(outbox.appended().getFirst())
                .isEqualTo(
                    new RecordingOutboxAppender.Appended(
                        generation.getWorkspaceId(),
                        "task",
                        taskId.toString(),
                        "task.draft_generated",
                        Map.of())));
  }

  @Test
  @DisplayName("생성 결과가 baseline과 같으면 generated로 닫되 event를 발행하지 않는다")
  void unchangedDraftCompletesAsGeneratedWithoutEvent() {
    // GENERATED_TITLE_FALLBACK은 성공 outcome이면서 title을 convert 시점 고정 fallback과 같은 규칙으로
    // 만든다. role·priority까지 겹치면 세 값이 정확히 일치하고, 그때 tasks는 반영해도 그대로다. 이 경우까지
    // 발행하면 worker가 바뀐 것 없는 projection을 다시 hydrate한다(5.4절).
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();

    ledger.record(
        claim,
        new AsyncGenerationResult(
            new TaskDraft("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.MEDIUM),
            GenerationOutcome.GENERATED_TITLE_FALLBACK));

    TaskDraftGeneration generation = reload(taskId);
    assertAll(
        // 생성 자체는 성공이므로 종료 사유는 그대로 generated다.
        () -> assertThat(generation.getStatus()).isEqualTo("completed"),
        () -> assertThat(generation.getCompletionReason()).isEqualTo("generated"),
        () -> assertThat(outbox.appended()).isEmpty());
  }

  @Test
  @DisplayName("baseline이 달라졌으면 user_edited로 닫고 event를 발행하지 않는다")
  void baselineMismatchClosesAsUserEdited() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();
    applier.willReturn(TaskDraftApplyResult.BASELINE_MISMATCH);

    ledger.record(claim, result(GenerationOutcome.GENERATED));

    assertAll(
        () -> assertThat(reload(taskId).getCompletionReason()).isEqualTo("user_edited"),
        // tasks가 그대로이므로 projection도 바뀔 것이 없다(5.4절).
        () -> assertThat(outbox.appended()).isEmpty());
  }

  @Test
  @DisplayName("대상 task가 사라졌으면 task_gone으로 닫고 event를 발행하지 않는다")
  void goneTaskClosesAsTaskGone() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();
    applier.willReturn(TaskDraftApplyResult.TASK_GONE);

    ledger.record(claim, result(GenerationOutcome.GENERATED));

    assertAll(
        () -> assertThat(reload(taskId).getCompletionReason()).isEqualTo("task_gone"),
        () -> assertThat(outbox.appended()).isEmpty());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("반영이 실패하면 원장 전이도 함께 롤백된다")
  void applyFailureRollsBackLedgerTransition() {
    UUID taskId = inTransaction(this::persistPending);
    try {
      ClaimedGeneration claim = ledger.claimDue(10).getFirst();
      applier.willThrow(new IllegalStateException("반영 실패"));

      assertThatThrownBy(() -> ledger.record(claim, result(GenerationOutcome.GENERATED)))
          .isInstanceOf(IllegalStateException.class);

      // 종료가 커밋되면 안 된다. processing으로 남아야 lease 만료 후 회수돼 다시 시도된다(8.3·8.5절).
      TaskDraftGeneration generation = repository.findByTaskId(taskId).orElseThrow();
      assertAll(
          () -> assertThat(generation.getStatus()).isEqualTo("processing"),
          () -> assertThat(generation.getCompletionReason()).isNull(),
          () -> assertThat(generation.getClaimToken()).isEqualTo(claim.claimToken()));
    } finally {
      inTransaction(() -> repository.findByTaskId(taskId).ifPresent(repository::delete));
    }
  }

  @Test
  @DisplayName("retryable 실패는 결과 기록 시점부터 잰 백오프 뒤로 미룬다")
  void retryableFailureSchedulesBackoffFromRecordTime() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();
    Instant databaseNow = repository.currentDatabaseTime();

    ledger.record(claim, result(GenerationOutcome.TIMEOUT));

    TaskDraftGeneration generation = reload(taskId);
    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("pending"),
        () -> assertThat(generation.getNextAttemptAt()).isEqualTo(databaseNow.plus(FIRST_BACKOFF)),
        // 되돌릴 때 이전 소유권을 함께 정리한다(7.1절).
        () -> assertThat(generation.getClaimToken()).isNull(),
        () -> assertThat(generation.getLeaseExpiresAt()).isNull());
  }

  @Test
  @DisplayName("재시도 상한에 도달하면 retry_exhausted로 닫는다")
  void exhaustedRetriesCloseTheLedger() {
    UUID taskId = persistPending();

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      dueNow(taskId);
      List<ClaimedGeneration> claims = ledger.claimDue(10);
      assertThat(claims).hasSize(1);
      ledger.record(claims.getFirst(), result(GenerationOutcome.PROVIDER_ERROR));
      entityManager.flush();
      entityManager.clear();

      TaskDraftGeneration generation = reload(taskId);
      assertThat(generation.getAttemptCount()).isEqualTo(attempt);
      if (attempt < MAX_ATTEMPTS) {
        assertThat(generation.getStatus()).isEqualTo("pending");
      } else {
        assertThat(generation.getStatus()).isEqualTo("completed");
        assertThat(generation.getCompletionReason()).isEqualTo("retry_exhausted");
      }
    }
  }

  @Test
  @DisplayName("입력 부족과 설정 무효는 재시도 없이 각자의 사유로 닫는다")
  void terminalOutcomesCloseWithoutRetry() {
    UUID insufficient = persistPending();
    UUID invalidConfig = persistPending();
    List<ClaimedGeneration> claims = ledger.claimDue(10);

    for (ClaimedGeneration claim : claims) {
      GenerationOutcome outcome =
          claim.taskId().equals(insufficient)
              ? GenerationOutcome.INSUFFICIENT_CONTEXT
              : GenerationOutcome.INVALID_CONFIG;
      ledger.record(claim, result(outcome));
    }

    assertAll(
        () ->
            assertThat(reload(insufficient).getCompletionReason())
                .isEqualTo("insufficient_context"),
        () -> assertThat(reload(invalidConfig).getCompletionReason()).isEqualTo("invalid_config"),
        () -> assertThat(reload(insufficient).getAttemptCount()).isEqualTo(1));
  }

  @Test
  @DisplayName("반영 창을 지난 결과는 deadline_exceeded로 닫는다")
  void resultAfterApplyCutoffIsNotApplied() {
    UUID taskId = persistPending();
    ClaimedGeneration claim = ledger.claimDue(10).getFirst();
    // 실행 도중 반영 창이 닫힌 상황. 읽기 투영이 이미 ready로 알렸을 수 있어 반영하면 안 된다(8.6절).
    passApplyCutoff(taskId);

    ledger.record(claim, result(GenerationOutcome.GENERATED));

    assertThat(reload(taskId).getCompletionReason()).isEqualTo("deadline_exceeded");
  }

  @Test
  @DisplayName("반영 창을 지난 행은 claim 대상이 아니다")
  void doesNotClaimPastApplyCutoff() {
    passApplyCutoff(persistPending());

    // 결과를 쓸 수 없는 작업이므로 claim하면 provider 호출만 낭비된다.
    assertThat(ledger.claimDue(10)).isEmpty();
  }

  @Test
  @DisplayName("claim 후 기록 전에 종료된 상한 초과 행은 다음 claim이 종결한다")
  void claimFinalizesExhaustedLeftover() {
    UUID taskId = persistPending();
    entityManager
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations SET attempt_count = :maxAttempts,"
                + " status = 'processing', claim_token = gen_random_uuid(),"
                + " lease_expires_at = NOW() - INTERVAL '1 minute'"
                + " WHERE task_id = :taskId")
        .setParameter("maxAttempts", MAX_ATTEMPTS)
        .setParameter("taskId", taskId)
        .executeUpdate();
    entityManager.clear();

    assertThat(ledger.claimDue(10)).isEmpty();
    TaskDraftGeneration generation = reload(taskId);
    assertThat(generation.getStatus()).isEqualTo("completed");
    assertThat(generation.getCompletionReason()).isEqualTo("retry_exhausted");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("lease 만료 후 재claim되면 이전 claim의 결과를 무시한다")
  void staleClaimResultIsIgnored() {
    UUID taskId = inTransaction(this::persistPending);
    try {
      ClaimedGeneration stale = ledger.claimDue(10).getFirst();
      inTransaction(() -> expireLease(taskId));
      ClaimedGeneration current = ledger.claimDue(10).getFirst();
      assertThat(current.claimToken()).isNotEqualTo(stale.claimToken());

      // 뒤늦게 돌아온 이전 실행. fencing이 없으면 여기서 원장이 닫히고 현재 실행의 결과는 갈 곳을 잃는다.
      ledger.record(stale, result(GenerationOutcome.GENERATED));

      TaskDraftGeneration afterStale = repository.findByTaskId(taskId).orElseThrow();
      assertThat(afterStale.getStatus()).isEqualTo("processing");
      assertThat(afterStale.getClaimToken()).isEqualTo(current.claimToken());

      ledger.record(current, result(GenerationOutcome.GENERATED));
      assertThat(repository.findByTaskId(taskId).orElseThrow().getStatus()).isEqualTo("completed");
    } finally {
      inTransaction(() -> repository.findByTaskId(taskId).ifPresent(repository::delete));
    }
  }

  private AsyncGenerationResult result(GenerationOutcome outcome) {
    return new AsyncGenerationResult(
        new TaskDraft("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.HIGH), outcome);
  }

  private TaskDraftGeneration reload(UUID taskId) {
    entityManager.flush();
    entityManager.clear();
    return repository.findByTaskId(taskId).orElseThrow();
  }

  @Test
  @DisplayName("반영 창이 닫힌 pending을 deadline_exceeded로 닫는다")
  void closesAbandonedPending() {
    // 두 claim 쿼리가 apply_cutoff_at > NOW()를 요구하므로 이 행은 영영 집히지 않는다. 닫아 주는
    // 경로가 없으면 미종료로 영구히 남아 상태 gauge가 현재 적체와 버려진 행을 구분하지 못하고,
    // 부분 인덱스의 "미종료 집합은 유계" 전제도 깨진다(11.1절).
    UUID taskId = persistPending();
    passApplyCutoff(taskId);

    int closed = ledger.closeAbandoned(100);

    TaskDraftGeneration closedGeneration = repository.findByTaskId(taskId).orElseThrow();
    assertAll(
        () -> assertThat(closed).isEqualTo(1),
        () -> assertThat(closedGeneration.getStatus()).isEqualTo("completed"),
        // operationally_closed는 운영자가 명시적으로 끝내는 경우다. 이건 나이 상한 초과라 record()가
        // 같은 상황에 쓰는 사유와 같아야 한다.
        () -> assertThat(closedGeneration.getCompletionReason()).isEqualTo("deadline_exceeded"),
        () -> assertThat(repository.snapshotUnfinished().getPending()).isZero());
  }

  @Test
  @DisplayName("lease가 살아 있는 processing은 닫지 않는다")
  void keepsInFlightGenerationUntilItsResultArrives() {
    // 지금 누군가 실행 중이고 그 결과는 record()의 cutoff 분기가 닫는다. 여기서 먼저 닫으면 claim
    // token이 지워져 그 결과가 stale로 잘못 집계된다.
    UUID taskId = persistPending();
    ledger.claimDue(1);
    passApplyCutoff(taskId);

    assertThat(ledger.closeAbandoned(100)).isZero();
  }

  @Test
  @DisplayName("워커가 사라진 processing은 lease가 만료된 뒤 닫는다")
  void closesInFlightGenerationOnceItsLeaseExpires() {
    UUID taskId = persistPending();
    ledger.claimDue(1);
    passApplyCutoff(taskId);
    expireLease(taskId);

    assertThat(ledger.closeAbandoned(100)).isEqualTo(1);
  }

  /** lease만 되돌린다. {@code next_attempt_at}은 그대로 두어 만료 회수 경로만 재현한다. */
  private void expireLease(UUID taskId) {
    entityManager
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations"
                + " SET lease_expires_at = NOW() - INTERVAL '1 minute' WHERE task_id = :taskId")
        .setParameter("taskId", taskId)
        .executeUpdate();
    entityManager.clear();
  }

  /** 백오프 대기를 지나게 한다. lease는 건드리지 않아 pending 재시도 경로만 재현한다. */
  private void dueNow(UUID taskId) {
    entityManager
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations"
                + " SET next_attempt_at = NOW() - INTERVAL '1 minute' WHERE task_id = :taskId")
        .setParameter("taskId", taskId)
        .executeUpdate();
    entityManager.clear();
  }

  private void passApplyCutoff(UUID taskId) {
    entityManager
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations"
                + " SET apply_cutoff_at = NOW() - INTERVAL '1 second' WHERE task_id = :taskId")
        .setParameter("taskId", taskId)
        .executeUpdate();
    entityManager.clear();
  }

  private UUID persistPending() {
    UUID taskId = UUID.randomUUID();
    // 기준을 DB 시계로 잡는다. PostgreSQL의 NOW()는 문장 시각이 아니라 트랜잭션 시작 시각이므로, 테스트
    // 트랜잭션 안에서 애플리케이션 시계로 적재하면 next_attempt_at이 NOW()보다 뒤가 되어 claim 대상에서
    // 빠진다. 이 성질은 8.6절이 반영 CAS의 한계로 지적한 것과 같은 것이다.
    Instant enrolledAt = repository.currentDatabaseTime();
    Instant readDeadline = enrolledAt.plus(10, ChronoUnit.MINUTES);
    repository.save(
        TaskDraftGeneration.builder()
            .workspaceId(UUID.randomUUID())
            .taskId(taskId)
            .signalTitle("결제 실패율이 올라감")
            .signalType("risk")
            .signalDescription("카드 결제 실패가 늘었다")
            .signalImpact("결제 전환율 하락")
            .signalEvidence("[{\"target\":\"결제 화면\",\"change\":\"실패율 3%\",\"impact\":\"전환 하락\"}]")
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

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    return new TransactionTemplate(transactionManager).execute(status -> action.get());
  }

  private void inTransaction(Runnable action) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              action.run();
            });
  }
}
