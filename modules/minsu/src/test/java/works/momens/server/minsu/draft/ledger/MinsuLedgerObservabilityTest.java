package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 원장 사건 지표의 이름·태그와 선등록을 고정한다(MOM-0821, 설계 9.3절). */
class MinsuLedgerObservabilityTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MinsuLedgerObservability observability =
      new MinsuLedgerObservability(meterRegistry);

  @Test
  @DisplayName("사건이 없어도 모든 종료 사유가 0으로 등록돼 있다")
  void preRegistersEveryCompletionReason() {
    assertThat(meterRegistry.find("momens.minsu.ledger.completion.attempts").summaries())
        .hasSize(CompletionReason.values().length);
    assertAll(
        Stream.of(CompletionReason.values())
            .map(reason -> () -> assertThat(completions(reason.value())).isZero()));
  }

  @Test
  @DisplayName("적재 성패와 나머지 counter도 0으로 등록돼 있다")
  void preRegistersRemainingCounters() {
    assertAll(
        () -> assertThat(enrollments("success")).isZero(),
        () -> assertThat(enrollments("failure")).isZero(),
        () -> assertThat(counter("momens.minsu.ledger.reclaims")).isZero(),
        () -> assertThat(counter("momens.minsu.ledger.stale.results")).isZero(),
        () -> assertThat(counter("momens.minsu.ledger.deadline.projections")).isZero());
  }

  @Test
  @DisplayName("태그 없는 timer도 선등록 대상이다")
  void preRegistersUntaggedTimers() {
    // 규약의 기준은 값이 아니라 태그 조합이 부팅 시점에 정해지는가다. 태그가 없으면 조합은 자명하게
    // 확정이고, 첫 사건 전까지 _count가 없어 rate()가 값을 못 내는 문제도 그대로 생긴다.
    assertAll(
        () ->
            assertThat(meterRegistry.get("momens.minsu.ledger.claim.wait.duration").timer().count())
                .isZero(),
        () ->
            assertThat(meterRegistry.get("momens.minsu.ledger.generation.duration").timer().count())
                .isZero());
  }

  @Test
  @DisplayName("소진에 이를 수 있는 실패 종류를 0으로 등록하고 원인별로 센다")
  void countsRetryExhaustionByFailureReason() {
    // 종료 사유는 retry_exhausted 하나라 timeout 소진과 invalid_output 소진이 섞인다. 두 실패의
    // 대응이 정반대라 원인을 따로 세지 않으면 값을 조정할 근거가 되지 못한다(9.2절).
    assertThat(meterRegistry.find("momens.minsu.ledger.retry.exhaustions").counters()).hasSize(5);

    observability.recordRetryExhaustion("timeout");

    assertAll(
        () -> assertThat(retryExhaustions("timeout")).isEqualTo(1),
        () -> assertThat(retryExhaustions("invalid_output")).isZero(),
        () -> assertThat(retryExhaustions("none")).isZero());
  }

  @Test
  @DisplayName("지표 하나가 종료 건수와 시도 분포를 함께 준다")
  void recordsCompletionCountAndAttemptDistribution() {
    observability.recordCompletion(CompletionReason.RETRY_EXHAUSTED, 4);
    observability.recordCompletion(CompletionReason.RETRY_EXHAUSTED, 2);

    // count가 종료 건수, sum이 누적 시도 수다. 종료 counter를 따로 두면 그 값이 count와 항상 같아
    // 순수한 중복이 되고, 대시보드가 서로 다른 쪽을 참조하기 시작하면 되돌릴 수 없다.
    assertAll(
        () -> assertThat(completions("retry_exhausted")).isEqualTo(2),
        () -> assertThat(summary("retry_exhausted").totalAmount()).isEqualTo(6));
  }

  @Test
  @DisplayName("회수가 없으면 counter를 올리지 않는다")
  void skipsReclaimCounterWhenNothingReclaimed() {
    observability.recordReclaims(0);
    observability.recordReclaims(2);

    assertThat(counter("momens.minsu.ledger.reclaims")).isEqualTo(2);
  }

  @Test
  @DisplayName("시계 차이로 음수가 된 구간은 0으로 기록한다")
  void clampsNegativeDurations() {
    observability.recordClaimWait(Duration.ofSeconds(-3));
    observability.recordGenerationDuration(Duration.ofSeconds(-3));

    assertAll(
        () ->
            assertThat(
                    meterRegistry
                        .get("momens.minsu.ledger.claim.wait.duration")
                        .timer()
                        .totalTime(TimeUnit.SECONDS))
                .isZero(),
        () ->
            assertThat(
                    meterRegistry
                        .get("momens.minsu.ledger.generation.duration")
                        .timer()
                        .totalTime(TimeUnit.SECONDS))
                .isZero());
  }

  @Test
  @DisplayName("트랜잭션 안에서는 커밋 전까지 영속 상태 지표를 올리지 않는다")
  void defersStateAssertingMetricsUntilCommit() {
    // counter는 롤백되지 않는다. 트랜잭션 안에서 바로 올리면 그 트랜잭션이 뒤집혔을 때 원장은
    // processing으로 돌아가는데 종료 counter만 남고, 그 행이 회수돼 다시 닫히면 두 번 세어진다.
    TransactionSynchronizationManager.initSynchronization();
    try {
      observability.recordCompletion(CompletionReason.GENERATED, 1);
      observability.recordEnrollment(true);
      observability.recordReclaims(1);

      assertAll(
          () -> assertThat(completions("generated")).isZero(),
          () -> assertThat(enrollments("success")).isZero(),
          () -> assertThat(counter("momens.minsu.ledger.reclaims")).isZero());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("상태를 바꾸지 않는 관측과 적재 실패는 트랜잭션 안에서도 즉시 올린다")
  void recordsObservationsImmediately() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      // 앞의 둘은 읽기만 하고 끝나 미룰 이유가 없다. 적재 실패는 그 트랜잭션이 어차피 커밋되지
      // 않으므로 미루면 기록이 사라진다.
      observability.recordStaleResult();
      observability.recordDeadlineProjection();
      observability.recordEnrollment(false);

      assertAll(
          () -> assertThat(counter("momens.minsu.ledger.stale.results")).isEqualTo(1),
          () -> assertThat(counter("momens.minsu.ledger.deadline.projections")).isEqualTo(1),
          () -> assertThat(enrollments("failure")).isEqualTo(1));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private double completions(String reason) {
    return summary(reason).count();
  }

  private DistributionSummary summary(String reason) {
    return meterRegistry
        .get("momens.minsu.ledger.completion.attempts")
        .tag("completion.reason", reason)
        .summary();
  }

  private double retryExhaustions(String failureReason) {
    return meterRegistry
        .get("momens.minsu.ledger.retry.exhaustions")
        .tag("failure.reason", failureReason)
        .counter()
        .count();
  }

  private double counter(String name) {
    return meterRegistry.get(name).counter().count();
  }

  private double enrollments(String outcome) {
    return meterRegistry
        .get("momens.minsu.ledger.enrollments")
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
