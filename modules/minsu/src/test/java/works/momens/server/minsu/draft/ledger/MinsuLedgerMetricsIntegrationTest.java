package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.Reference;
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
import org.springframework.dao.DataAccessResourceFailureException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * 원장 운영 지표를 실제 PostgreSQL로 검증한다(MOM-0821, 설계 9.3절).
 *
 * <p>집계가 SQL에 있으므로 실제 DB로 봐야 의미가 있다. {@code FILTER} 절과 {@code NOW()} 비교, 미종료로 스캔을 좁히는 WHERE가 모두 여기서
 * 확정된다.
 *
 * <p>스케줄러는 쓰지 않고 {@code refresh()}를 직접 호출한다. 주기를 기다리면 테스트가 느려지고 타이밍에 흔들린다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class MinsuLedgerMetricsIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("첫 스냅샷 전에도 모든 게이지가 0으로 등록돼 있다")
  void preRegistersGaugesBeforeFirstSnapshot() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(repository, meterRegistry);

    assertAll(
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.generations", "pending"))
                .isZero(),
        () ->
            assertThat(
                    gauge(metrics, meterRegistry, "momens.minsu.ledger.generations", "processing"))
                .isZero(),
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.oldest.unfinished.age"))
                .isZero(),
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.expired.leases"))
                .isZero(),
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.expired.lease.max.age"))
                .isZero(),
        () ->
            assertThat(
                    gauge(
                        metrics,
                        meterRegistry,
                        "momens.minsu.ledger.deadline.exceeded.generations"))
                .isZero());
  }

  @Test
  @DisplayName("미종료 원장만 집계하고 completed는 세지 않는다")
  void countsOnlyUnfinishedGenerations() {
    backdate(persist(pending()), 120);
    persist(pending());
    persistProcessing(Instant.now().plus(1, ChronoUnit.MINUTES));
    // 미종료 중 가장 오래된 것보다 더 오래된 종료 행. 나이 집계가 이걸 집으면 값이 600 쪽으로 튄다.
    UUID finished = persist(pending());
    backdate(finished, 600);
    completed(finished);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(repository, meterRegistry);

    metrics.refresh();

    assertAll(
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.generations", "pending"))
                .isEqualTo(2),
        () ->
            assertThat(
                    gauge(metrics, meterRegistry, "momens.minsu.ledger.generations", "processing"))
                .isEqualTo(1),
        // 종료된 행은 나이 집계에서도 빠져야 한다. 남아 있으면 테이블이 쌓일수록 age가 계속 커진다.
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.oldest.unfinished.age"))
                .isBetween(115.0, 125.0));
  }

  @Test
  @DisplayName("만료된 lease의 수와 최대 나이를 낸다")
  void reportsExpiredLeases() {
    persistProcessing(Instant.now().minus(30, ChronoUnit.SECONDS));
    persistProcessing(Instant.now().minus(90, ChronoUnit.SECONDS));
    // 아직 유효한 lease는 만료 집계에서 빠진다.
    persistProcessing(Instant.now().plus(1, ChronoUnit.MINUTES));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(repository, meterRegistry);

    metrics.refresh();

    assertAll(
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.expired.leases"))
                .isEqualTo(2),
        // 가장 오래 만료된 쪽(90초) 기준이다. 30초 쪽이 나오면 최대가 아니라 최소를 보고 있다는 뜻이다.
        // 정확히 90이 아닌 이유는 Postgres NOW()가 트랜잭션 시작 시각이어서다. 이 테스트는 삽입과 집계가
        // 한 트랜잭션이라 기준 시각이 삽입보다 이르다. 운영에서는 refresh()마다 트랜잭션이 새로 열려
        // 오차가 밀리초 단위다.
        () ->
            assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.expired.lease.max.age"))
                .isBetween(85.0, 95.0));
  }

  @Test
  @DisplayName("read_deadline_at을 지난 미종료 원장을 센다")
  void countsReadDeadlineExceeded() {
    Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
    persist(pending().readDeadlineAt(past).applyCutoffAt(past.minus(1, ChronoUnit.MINUTES)));
    persist(pending());
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(repository, meterRegistry);

    metrics.refresh();

    assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.deadline.exceeded.generations"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("스냅샷이 갱신되면 나이가 되돌아간다")
  void resetsSnapshotAgeOnRefresh() throws InterruptedException {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(repository, meterRegistry);
    Thread.sleep(50);
    double beforeRefresh = gauge(metrics, meterRegistry, "momens.minsu.ledger.snapshot.age");

    metrics.refresh();

    assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.snapshot.age"))
        .isLessThan(beforeRefresh)
        .isNotNegative();
  }

  @Test
  @DisplayName("스냅샷 갱신이 실패하면 나이를 되돌리지 않는다")
  void keepsSnapshotAgeGrowingWhenRefreshFails() throws InterruptedException {
    // 조회가 실패해도 직전 값이 남아 있으므로, 나이까지 갱신하면 낡은 숫자를 최신인 것처럼 계속
    // 보고하게 된다. 지표가 멀쩡해 보이는 것이 가장 나쁘다.
    TaskDraftGenerationRepository failing = mock(TaskDraftGenerationRepository.class);
    when(failing.snapshotUnfinished()).thenThrow(new DataAccessResourceFailureException("DB 장애"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    MinsuLedgerMetrics metrics = new MinsuLedgerMetrics(failing, meterRegistry);
    Thread.sleep(30);
    double beforeFailure = gauge(metrics, meterRegistry, "momens.minsu.ledger.snapshot.age");

    metrics.refresh();

    assertThat(gauge(metrics, meterRegistry, "momens.minsu.ledger.snapshot.age"))
        .isGreaterThan(beforeFailure);
  }

  /**
   * 미종료 부분 인덱스가 집계 쿼리의 술어에 대해 <b>사용 가능한지</b>만 본다.
   *
   * <p>부분 인덱스는 술어가 쿼리 WHERE와 맞아야 planner가 후보로 올린다. 테스트 데이터량에서는 seq scan이 정상적으로 더 싸서 실제 선택 여부로는 확인할
   * 수 없으므로, seq scan을 끄고 후보로 오르는지를 본다. 술어가 어긋나면 이 상태에서도 인덱스가 쓰이지 않는다.
   *
   * <p>EXPLAIN 대상은 <b>실제 집계 SQL 그대로</b>다. 테스트에 복사해 두면 WHERE를 바꿔도 복사본은 그대로라 통과한 채 인덱스만 놓치므로, 쿼리를 상수로
   * 빼 공유한다.
   */
  @Test
  @DisplayName("미종료 부분 인덱스가 집계 술어에 사용 가능하다")
  void unfinishedIndexIsUsableForAggregatePredicate() {
    persist(pending());
    entityManager
        .getEntityManager()
        .createNativeQuery("SET LOCAL enable_seqscan = off")
        .executeUpdate();

    @SuppressWarnings("unchecked")
    java.util.List<String> plan =
        entityManager
            .getEntityManager()
            .createNativeQuery("EXPLAIN " + TaskDraftGenerationRepository.SNAPSHOT_UNFINISHED_SQL)
            .getResultList();

    assertThat(String.join("\n", plan)).contains("idx_minsu_task_draft_generations_unfinished");
  }

  // gauge는 관찰 대상 객체를 weak reference로만 참조하므로, 객체가 GC되면 NaN을 반환합니다.
  // 객체를 지역 변수에 할당해도 마지막 사용 이후에는 GC될 수 있으므로 이것만으로는 충분하지 않습니다.
  // 따라서 관찰 대상 객체를 인자로 받고, gauge 값을 읽은 직후 reachabilityFence를 호출해
  // 이 시점까지 객체가 strongly reachable한 상태를 유지합니다. 아래 두 메서드 모두 같은 이유로 구현합니다.
  private double gauge(MinsuLedgerMetrics metrics, SimpleMeterRegistry meterRegistry, String name) {
    double value = meterRegistry.get(name).gauge().value();
    Reference.reachabilityFence(metrics);
    return value;
  }

  private double gauge(
      MinsuLedgerMetrics metrics, SimpleMeterRegistry meterRegistry, String name, String status) {
    double value = meterRegistry.get(name).tag("status", status).gauge().value();
    Reference.reachabilityFence(metrics);
    return value;
  }

  private UUID persist(TaskDraftGeneration.TaskDraftGenerationBuilder builder) {
    TaskDraftGeneration saved = repository.saveAndFlush(builder.build());
    return saved.getId();
  }

  /**
   * {@code created_at}을 과거로 민다.
   *
   * <p>이 컬럼은 JPA Auditing이 애플리케이션 시계로 쓰는데 나이는 DB의 {@code NOW()}로 뺀다. 게다가 {@code NOW()}는 트랜잭션 시작
   * 시각이라 삽입과 집계가 한 트랜잭션인 이 테스트에서는 갓 넣은 행의 나이가 0 근처(혹은 살짝 음수)가 된다. 나이 집계를 제대로 보려면 DB 시계 기준으로 값을 직접
   * 밀어야 한다.
   */
  private void backdate(UUID id, int seconds) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations "
                + "SET created_at = NOW() - make_interval(secs => :seconds) WHERE id = :id")
        .setParameter("seconds", seconds)
        .setParameter("id", id)
        .executeUpdate();
  }

  /** 종료 전이는 상태와 사유를 함께 세우는 CHECK가 있어 native update로 한 번에 맞춘다. */
  private void completed(UUID id) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            """
            UPDATE minsu_task_draft_generations
            SET status = 'completed', completion_reason = 'generated',
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = :id
            """)
        .setParameter("id", id)
        .executeUpdate();
  }

  private TaskDraftGeneration.TaskDraftGenerationBuilder pending() {
    Instant readDeadline = Instant.now().plus(10, ChronoUnit.MINUTES);
    return TaskDraftGeneration.builder()
        .workspaceId(WORKSPACE_ID)
        .taskId(UUID.randomUUID())
        .signalTitle("결제 실패율이 올라감")
        .signalType("risk")
        .signalDescription("2026-08-01부터 카드 결제 실패가 늘었다")
        .signalEvidence("[]")
        .baselineTitle("결제 실패율 대응")
        .baselineRole("backend")
        .baselinePriority("medium")
        .readDeadlineAt(readDeadline)
        .applyCutoffAt(readDeadline.minus(1, ChronoUnit.MINUTES))
        .nextAttemptAt(Instant.now());
  }

  /** 빌더는 적재 직후 상태만 만든다. {@code processing}은 실제 claim 전이를 거쳐야 claim token·lease 제약을 함께 만족한다. */
  private void persistProcessing(Instant leaseExpiresAt) {
    TaskDraftGeneration saved = repository.saveAndFlush(pending().build());
    saved.claim(UUID.randomUUID(), leaseExpiresAt);
    entityManager.flush();
  }
}
