package works.momens.server.minsu.draft.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import works.momens.server.minsu.draft.generation.GenerationOutcome;

/**
 * 원장의 사건 지표(docs/design/minsu-async-task-draft-design.md 9.3절).
 *
 * <p>상태 gauge는 {@link MinsuLedgerMetrics}가 주기 스냅샷으로 낸다. 여기는 사건이 일어난 그 자리에서 올리는 counter·timer만 모은다.
 * 원장 전이가 여러 파일에 흩어져 있어 {@code MeterRegistry}를 직접 쓰게 두면 이름과 태그 어휘가 갈리기 쉽다.
 *
 * <p>태그 조합이 부팅 시점에 정해지는 지표는 생성자에서 선등록한다(지표 규약). 기준은 계기 종류가 아니라 조합이 정해지는가이므로 summary도 대상이다. 값이 런타임에
 * 정해지는 timer는 해당하지 않는다.
 *
 * <p><b>영속 상태를 주장하는 지표는 커밋 후에 올린다.</b> counter는 롤백되지 않으므로 트랜잭션 안에서 올리면 그 트랜잭션이 뒤집혔을 때 원장과 지표가 갈린다.
 * 예컨대 종료 전이 뒤 outbox append가 실패하면 원장은 {@code processing}으로 되돌아가지만 종료 counter는 이미 올라간 상태로 남고, 그 행이
 * 나중에 회수돼 다시 종료되면 같은 사건이 두 번 세어진다. {@link #afterCommit}이 그 어긋남을 막는다.
 *
 * <p>반대로 <b>상태를 바꾸지 않는 관측은 즉시 올린다.</b> stale 결과와 deadline 투영은 읽기만 하고 끝나므로 미룰 이유가 없고, 적재 <i>실패</i>도
 * 그 트랜잭션이 어차피 커밋되지 않으므로 즉시 올려야 기록이 남는다.
 */
@Component
class MinsuLedgerObservability {

  private static final String COMPLETION_ATTEMPTS = "momens.minsu.ledger.completion.attempts";
  private static final String CLAIM_WAIT = "momens.minsu.ledger.claim.wait.duration";
  private static final String GENERATION_DURATION = "momens.minsu.ledger.generation.duration";

  /** 소진에 이를 수 있는 outcome만 선등록한다. 나머지는 재시도 없이 자기 사유로 닫히므로 이 지표에 도달하지 않는다(9.2절 표). */
  private static final GenerationOutcome[] RETRYABLE_OUTCOMES = {
    GenerationOutcome.TIMEOUT,
    GenerationOutcome.PROVIDER_ERROR,
    GenerationOutcome.INVALID_RESPONSE,
    GenerationOutcome.INVALID_OUTPUT
  };

  static final String NO_OUTCOME = "none";
  private static final String ENROLLMENTS = "momens.minsu.ledger.enrollments";
  private static final String COMPLETION_REASON = "completion.reason";

  private final MeterRegistry meterRegistry;

  MinsuLedgerObservability(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    for (CompletionReason reason : CompletionReason.values()) {
      completionAttempts(reason);
    }
    enrollments(true);
    enrollments(false);
    reclaims();
    staleResults();
    deadlineProjections();
    for (GenerationOutcome outcome : RETRYABLE_OUTCOMES) {
      retryExhaustions(outcome.reason());
    }
    retryExhaustions(NO_OUTCOME);
    timer(CLAIM_WAIT);
    timer(GENERATION_DURATION);
  }

  /**
   * 원장이 종료로 닫힐 때 사유와 그때까지의 시도 횟수를 함께 남긴다.
   *
   * <p><b>지표 하나가 두 질문에 답한다.</b> 관측 사건이 종료이고 관측 값이 그 시도 횟수라, {@code _count}가 사유별 종료 건수이고 {@code
   * _sum}이 <b>종료된 원장이 소모한 시도 수의 합</b>이다. 아직 {@code pending}·{@code processing}인 원장이 이미 쓴 시도는 빠지므로,
   * 이 값을 provider 비용 추정에 쓰면 진행 중 물량만큼 과소 집계된다. 종료 counter를 따로 두면 그 값이 {@code _count}와 항상 같아 순수한 중복이
   * 되고, 대시보드가 서로 다른 쪽을 참조하기 시작하면 되돌릴 수 없다.
   *
   * <p>시도 횟수는 claim 시점에 증가하므로(7.1절) <b>provider 호출 수가 아니라 claim 수</b>다. 실행되지 못하고 소모된 시도가 포함되며, 그
   * 차이는 hung attempt gauge와 같이 봐야 드러난다.
   */
  void recordCompletion(CompletionReason reason, int attemptCount) {
    afterCommit(() -> completionAttempts(reason).record(attemptCount));
  }

  /**
   * lease 만료로 회수한 수. stale 결과 수와 함께 오르면 lease가 짧다는 신호다(티켓 완료 조건).
   *
   * <p>회수 직후 시도 상한에 걸려 바로 닫히는 행도 포함한다. 잠근 시점에는 구분이 없기 때문이다. 그 몫은 stale 결과를 만들지 않으므로 위 짝 판정에 약간의 잡음이
   * 섞인다.
   */
  void recordReclaims(int count) {
    if (count > 0) {
      afterCommit(() -> reclaims().increment(count));
    }
  }

  /** claim token 불일치로 버린 결과 수. 위 회수 수와 짝을 이룬다(8.2절). */
  void recordStaleResult() {
    staleResults().increment();
  }

  /**
   * 재시도 시각이 지난 뒤 실제로 claim되기까지의 지연.
   *
   * <p>설계 9.3절이 적은 "claim 이후 모델 호출 시작까지의 queue delay"는 지금 구조에서 항상 0에 가깝다. scheduler가 빈 슬롯 수만큼만
   * claim하고 그만큼만 제출하므로 제출된 시도는 즉시 시작한다. 실제 지연은 그 앞에 있다. {@code fixedDelay}가 배치의 결과 기록까지 기다리므로 직전
   * 배치의 가장 느린 시도만큼 다음 claim이 밀린다({@link MinsuDrainScheduler} 참고). 그래서 재는 구간을 이쪽으로 옮겼다.
   */
  void recordClaimWait(Duration wait) {
    afterCommit(() -> timer(CLAIM_WAIT).record(clampToZero(wait)));
  }

  /**
   * 재시도 상한에 걸려 종료된 원인 분포.
   *
   * <p>{@code completion.attempts}만으로는 {@code timeout}으로 소진된 것과 {@code invalid_output}으로 소진된 것이 같은
   * 시계열에 섞인다. 두 실패의 대응이 정반대라 그대로 두면 값을 조정할 근거가 되지 못한다. 앞쪽이 편중되면 {@code attempt-timeout}·lease 조정이고
   * 뒤쪽이면 프롬프트·스키마 수정이다(9.2절).
   *
   * <p>사유를 {@code completion.attempts}의 태그로 더하지 않는 이유는 카디널리티다. 종료 사유 8 × 실패 종류가 곱해져 규약의 "10 미만 목표"를
   * 크게 넘는다. 소진에 이르는 실패 종류만 따로 세면 값 집합이 다섯으로 유계다.
   *
   * <p>claim 직후 프로세스가 죽어 잔여 행으로 닫히는 경로에는 outcome이 없어 {@code none}으로 센다.
   */
  void recordRetryExhaustion(String failureReason) {
    afterCommit(() -> retryExhaustions(failureReason).increment());
  }

  /**
   * 적재부터 {@code tasks} 반영까지의 end-to-end 지연. 성공 반영에서만 기록한다.
   *
   * <p>시작 시각인 {@code created_at}은 JPA Auditing이 애플리케이션 시계로 쓰고 끝 시각은 DB 시계라, 편차에 따라 음수가 나올 수 있어 0에서
   * 자른다({@link TaskDraftGenerationRepository#snapshotUnfinished}의 나이와 같은 사정이다).
   */
  void recordGenerationDuration(Duration duration) {
    afterCommit(() -> timer(GENERATION_DURATION).record(clampToZero(duration)));
  }

  /**
   * 원장 적재의 성패. 실패율은 fail-closed 선택의 대가를 보는 값이다(5.3절).
   *
   * <p>성공은 호출자인 convert 트랜잭션이 커밋된 뒤에 올린다. 적재 자체가 성공해도 그 트랜잭션이 다른 이유로 뒤집히면 원장에 남는 것이 없기 때문이다. 실패는
   * 반대로 즉시 올린다. 그 트랜잭션은 어차피 커밋되지 않으므로 미루면 기록이 사라진다.
   */
  void recordEnrollment(boolean success) {
    if (success) {
      afterCommit(() -> enrollments(true).increment());
    } else {
      enrollments(false).increment();
    }
  }

  /**
   * 읽기 투영이 deadline으로 {@code ready}를 돌려준 수. <b>0이 정상이며 경보 대상이다</b>(MOM-0832).
   *
   * <p>사용자가 조회해야 올라가므로 정지를 늦게 안다. 그래서 스냅샷의 {@code deadline.exceeded.generations} gauge가 따로 있다. 둘은
   * 같은 사건을 다른 시점에서 보는 값이다.
   */
  void recordDeadlineProjection() {
    deadlineProjections().increment();
  }

  private DistributionSummary completionAttempts(CompletionReason reason) {
    return DistributionSummary.builder(COMPLETION_ATTEMPTS)
        .baseUnit("attempts")
        .tag(COMPLETION_REASON, reason.value())
        .register(meterRegistry);
  }

  private Counter enrollments(boolean success) {
    return meterRegistry.counter(ENROLLMENTS, "outcome", success ? "success" : "failure");
  }

  private Counter reclaims() {
    return meterRegistry.counter("momens.minsu.ledger.reclaims");
  }

  private Counter staleResults() {
    return meterRegistry.counter("momens.minsu.ledger.stale.results");
  }

  private Counter deadlineProjections() {
    return meterRegistry.counter("momens.minsu.ledger.deadline.projections");
  }

  private Counter retryExhaustions(String failureReason) {
    return meterRegistry.counter(
        "momens.minsu.ledger.retry.exhaustions", "failure.reason", failureReason);
  }

  private Timer timer(String name) {
    return meterRegistry.timer(name);
  }

  /**
   * 활성 트랜잭션이 커밋된 뒤에 실행한다. 트랜잭션이 없으면 즉시 실행한다.
   *
   * <p>즉시 실행 경로는 테스트와 트랜잭션 밖 호출을 위한 것이다. 커밋할 것이 없으면 미룰 것도 없다.
   */
  private static void afterCommit(Runnable record) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      record.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            record.run();
          }
        });
  }

  private static Duration clampToZero(Duration duration) {
    return duration.isNegative() ? Duration.ZERO : duration;
  }
}
