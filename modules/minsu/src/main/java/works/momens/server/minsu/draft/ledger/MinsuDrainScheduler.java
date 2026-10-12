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
 * 생성 원장 drain.
 *
 * <p>drain 축이 꺼져 있으면 이 빈이 등록되지 않아 폴링 자체가 없다. 스케줄링 인프라는 조립 모듈 {@code app}이 항상 켜므로(MOM-0816) 이
 * scheduler는 {@code notification}의 push 설정과 무관하게 자신의 설정 축만으로 동작 여부가 정해진다.
 *
 * <p>주기를 1초로 둔 이유는 원장을 늦게 집을수록 앱이 {@code generating}을 보는 시간이 길어지기 때문이다. 빈 폴링은 {@code
 * (next_attempt_at) WHERE status = 'pending'} 부분 인덱스 덕에 싸다.
 *
 * <p><b>다만 1초가 claim 간격의 하한은 아니다.</b> {@code fixedDelay}는 {@code runPass}가 끝난 뒤부터 세고 {@code
 * runPass}는 배치의 결과 기록까지 블로킹하므로, 실제 하한은 <b>직전 배치에서 가장 느린 시도 + 결과 기록 + 1초</b>다. 그 사이 빈 슬롯이 있어도 새로 적재된
 * 원장은 집히지 않는다. 배치 전체가 하나의 wall-clock 상한을 공유하므로 이 지연의 상한은 {@code execution.attempt-timeout}이다.
 *
 * <p>{@code notification}의 {@code PushSender.runSendPass}처럼 패스 안에서 claim을 반복하는 방식은 여기서 듣지 않는다. 그쪽은
 * 발송 슬롯이 아니라 배치 크기가 한계라 라운드를 돌리면 백로그가 줄지만, minsu는 배치가 슬롯을 다 채운 상태라 라운드를 더 돌려도 claim이 0이다. 지연을 실제로
 * 줄이려면 결과 기록을 워커 스레드로 내려 패스를 논블로킹으로 만들어야 하고, 그것은 이 슬라이스를 넘는다. MVP 물량에서는 수용하고, 값의 재측정은 MOM-0824다.
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
