package works.momens.server.minsu.draft.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 원장 운영 지표(docs/design/minsu-async-task-draft-design.md 9.3절).
 *
 * <p><b>주기 스냅샷 방식이다.</b> gauge 콜백은 수집 시점마다 호출되므로 거기서 DB를 조회하면 수집 주기와 인스턴스 수만큼 쿼리가 곱해진다. 지표
 * 규약(docs/rules/observability.md)이 금지하는 형태다. 주기적으로 집계 한 번을 돌려 스냅샷을 갈아끼우고 gauge는 그 값을 읽기만 한다. 여섯 값이
 * 같은 시점의 일관된 단면이 되는 것은 덤이다.
 *
 * <p><b>drain 축에 걸지 않는다.</b> {@link MinsuDrainScheduler}와 다르다. 설정 rollback으로 drain을 끈 뒤 남은 {@code
 * pending}을 보는 것이 이 지표의 핵심 용도인데(11.1절), 같은 조건을 걸면 정작 그때 보이지 않는다.
 *
 * <p><b>스냅샷이 멈추면 낡은 값을 최신인 것처럼 보고한다.</b> 조회가 실패해도 직전 값이 남기 때문이다. 지표가 멀쩡해 보이는 것이 가장 나쁘므로 스냅샷 나이를 별도
 * gauge로 낸다. 그 값이 주기보다 훨씬 크면 아래 숫자들을 믿으면 안 된다는 뜻이다.
 *
 * <p><b>인스턴스마다 같은 전역 집계를 보고한다.</b> pod가 둘이면 {@code pending} 시계열도 둘이고 두 값이 같다. 대시보드에서 {@code sum}으로
 * 묶으면 값이 배가 되므로 {@code max}로 봐야 한다. 인스턴스 구분은 수집 계층이 붙이는 것이라 여기서 막을 수 없다.
 */
@Slf4j
@Component
class MinsuLedgerMetrics {

  private static final String GENERATIONS = "momens.minsu.ledger.generations";
  private static final String SECONDS = "seconds";

  private final TaskDraftGenerationRepository repository;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

  /** 마지막으로 성공한 스냅샷 시각. 첫 주기 전에도 나이가 0에서 자라도록 생성 시점으로 시작한다. */
  private volatile long refreshedAtNanos = System.nanoTime();

  /** 직전 주기가 실패했는지. 지속 실패를 매 주기 로그로 남기지 않기 위한 것이다. */
  private volatile boolean failing;

  MinsuLedgerMetrics(TaskDraftGenerationRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    // 생성자에서 등록하므로 첫 사건이 없어도 시계열이 존재한다(지표 규약의 "0 선등록").
    Gauge.builder(GENERATIONS, this, metrics -> metrics.snapshot.get().pending())
        .tag("status", GenerationStatus.PENDING.value())
        .register(meterRegistry);
    Gauge.builder(GENERATIONS, this, metrics -> metrics.snapshot.get().processing())
        .tag("status", GenerationStatus.PROCESSING.value())
        .register(meterRegistry);
    registerSeconds(
        meterRegistry,
        "momens.minsu.ledger.oldest.unfinished.age",
        metrics -> metrics.snapshot.get().oldestUnfinishedAgeSeconds());
    Gauge.builder(
            "momens.minsu.ledger.expired.leases",
            this,
            metrics -> metrics.snapshot.get().expiredLeases())
        .register(meterRegistry);
    registerSeconds(
        meterRegistry,
        "momens.minsu.ledger.expired.lease.max.age",
        metrics -> metrics.snapshot.get().expiredLeaseMaxAgeSeconds());
    Gauge.builder(
            "momens.minsu.ledger.deadline.exceeded.generations",
            this,
            metrics -> metrics.snapshot.get().readDeadlineExceeded())
        .register(meterRegistry);
    registerSeconds(meterRegistry, "momens.minsu.ledger.snapshot.age", MinsuLedgerMetrics::age);
  }

  /**
   * 미종료 원장을 집계해 스냅샷을 교체한다.
   *
   * <p>읽기 전용 트랜잭션 하나로 끝난다. 실패하면 스냅샷과 나이를 <b>갱신하지 않는다.</b> 갱신하면 조회가 계속 실패하는 동안에도 나이가 0으로 유지돼 정지가
   * 드러나지 않는다.
   */
  // initialDelay를 두지 않는다. 기본값 0이라 스케줄러가 뜨는 즉시 한 번 돌고, @Scheduled 등록은
  // 컨텍스트 refresh 이후라 그 시점에 repository는 이미 준비돼 있다. 주기만큼 미루면 그 사이 여섯
  // gauge가 EMPTY를 읽어 0을 보고하는데, snapshot.age도 같은 구간에서 0부터 자라므로 "아직 한 번도
  // 돌지 않음"과 "방금 성공함"이 모든 지표에서 똑같이 보인다. 롤링 배포마다 그 창이 열린다.
  // 값은 MinsuTaskDraftProperties.Metrics가 바인딩·검증하고, 여기서는 같은 키를 placeholder로 읽는다.
  // @ConfigurationProperties 빈은 prefix 기반 이름을 받아 SpEL 빈 참조로 가리킬 수 없다. 키를 잘못
  // 적으면 양쪽 모두 기본값으로 떨어지는 것은 남는 한계다.
  @Scheduled(fixedDelayString = "${momens.minsu.task-draft.metrics.snapshot-interval:10s}")
  void refresh() {
    try {
      snapshot.set(
          Snapshot.of(
              repository.snapshotUnfinished(
                  GenerationStatus.PENDING.value(), GenerationStatus.PROCESSING.value())));
      refreshedAtNanos = System.nanoTime();
      failing = false;
    } catch (RuntimeException e) {
      // 실패로 <b>전이할 때만</b> 남긴다. 주기가 10초라 지속 실패를 매번 찍으면 하루 8천 건이 쌓이는데,
      // 그 사이 새로 알 수 있는 것이 없다. 실패가 계속되는지는 snapshot.age gauge가 이미 보고한다.
      if (!failing) {
        failing = true;
        log.error("Minsu 원장 지표 스냅샷 갱신 실패. 복구까지 이 로그를 반복하지 않습니다", e);
      }
    }
  }

  private void registerSeconds(
      MeterRegistry meterRegistry, String name, ToDoubleFunction<MinsuLedgerMetrics> value) {
    Gauge.builder(name, this, value).baseUnit(SECONDS).register(meterRegistry);
  }

  private double age() {
    return (System.nanoTime() - refreshedAtNanos) / 1_000_000_000.0;
  }

  /**
   * 한 시점의 원장 단면.
   *
   * <p>첫 주기 전에는 {@link #EMPTY}다. 값이 0이라 "미종료 없음"과 구분되지 않지만, 그 구분은 스냅샷 나이 gauge가 한다.
   */
  private record Snapshot(
      long pending,
      long processing,
      double oldestUnfinishedAgeSeconds,
      long expiredLeases,
      double expiredLeaseMaxAgeSeconds,
      long readDeadlineExceeded) {

    private static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0);

    private static Snapshot of(LedgerSnapshotRow row) {
      return new Snapshot(
          row.getPending(),
          row.getProcessing(),
          row.getOldestUnfinishedAgeSeconds(),
          row.getExpiredLeases(),
          row.getExpiredLeaseMaxAgeSeconds(),
          row.getReadDeadlineExceeded());
    }
  }
}
