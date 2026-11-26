package works.momens.server.minsu.draft.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.generation.AsyncGenerationResult;
import works.momens.server.minsu.draft.generation.AsyncTaskDraftExecutor;

/**
 * 생성 원장 drain. push 설정과 독립적으로 drain 설정이 빈 등록과 폴링 여부를 결정한다.
 *
 * <p>{@code fixedDelay}는 배치 결과 기록이 끝난 뒤부터 센다. 실행 중 빈 슬롯이 생겨도 다음 주기까지 신규 원장을 claim하지 않으므로, 설정한 주기가
 * 실제 claim 간격을 보장하지 않는다. 실행 대기에는 {@code execution.attempt-timeout} 상한을 적용하지만 DB 처리 시간은 별도다. 기본 주기의
 * dev 실측은 MOM-0824에서 다룬다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "momens.minsu.task-draft.async.drain", havingValue = "true")
class MinsuDrainScheduler {

  private final MinsuConfigStatus configStatus;
  private final TaskDraftGenerationLedger ledger;
  private final AsyncTaskDraftExecutor executor;

  /**
   * 마지막으로 성공한 주기 시각. 이 값이 자라면 drain이 멈춘 것이다.
   *
   * <p>이 빈 자체가 drain 축에 걸려 있어 <b>축을 끄면 지표도 사라진다.</b> 부재를 정지로 읽으면 안 된다. 원장이 쌓이는지는 축과 무관하게 도는 {@link
   * MinsuLedgerMetrics} 쪽 gauge가 본다.
   */
  private volatile long lastSuccessNanos = System.nanoTime();

  MinsuDrainScheduler(
      MinsuConfigStatus configStatus,
      TaskDraftGenerationLedger ledger,
      AsyncTaskDraftExecutor executor,
      MeterRegistry meterRegistry) {
    this.configStatus = configStatus;
    this.ledger = ledger;
    this.executor = executor;
    Gauge.builder("momens.minsu.drain.heartbeat.age", this, MinsuDrainScheduler::heartbeatAge)
        .baseUnit("seconds")
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "1s")
  void drain() {
    try {
      runPass();
      // 예외 없이 끝난 주기만 heartbeat를 갱신한다. catch에서도 갱신하면 매 주기 실패하는 상태가
      // 정상으로 보인다.
      lastSuccessNanos = System.nanoTime();
    } catch (RuntimeException e) {
      // claim은 커밋됐고 결과 기록만 실패했을 수 있다. 그 원장은 lease 만료 후 회수된다.
      log.error("Minsu draft drain 주기 실패", e);
    }
  }

  private double heartbeatAge() {
    return (System.nanoTime() - lastSuccessNanos) / 1_000_000_000.0;
  }

  private void runPass() {
    if (!configStatus.enabled() || !configStatus.valid()) {
      // provider가 비활성이거나 설정이 무효면 claim하지 않는다. 원장은 pending 그대로 두고
      // completion_reason을 기록하지 않는다. 설정을 고치면 그대로 이어서 처리할 수 있는 작업을 종료로
      // 기록하면 되살릴 방법이 없기 때문이다. 그 전에 read_deadline_at이 지나면 읽기 투영이 닫는다.
      return;
    }
    int slots = executor.availableSlots();
    if (slots == 0) {
      // 멈춘 호출이 슬롯을 모두 점유한 상태다. 여기서 claim하면 실행되지 못한 작업이 시도
      // 횟수만 소모한다.
      return;
    }
    List<ClaimedGeneration> claims = ledger.claimDue(slots);
    if (claims.isEmpty()) {
      return;
    }
    List<AsyncGenerationResult> results =
        executor.executeAll(claims.stream().map(ClaimedGeneration::input).toList());
    for (int i = 0; i < claims.size(); i++) {
      ledger.record(claims.get(i), results.get(i));
    }
  }
}
