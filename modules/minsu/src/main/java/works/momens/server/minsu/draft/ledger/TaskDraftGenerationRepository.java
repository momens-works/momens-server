package works.momens.server.minsu.draft.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TaskDraftGenerationRepository extends JpaRepository<TaskDraftGeneration, UUID> {

  /**
   * {@link #snapshotUnfinished}의 SQL. 상수로 뺀 이유는 테스트가 <b>이 문장 그대로</b> EXPLAIN해 부분 인덱스가 실제로 쓰이는지 보기
   * 위해서다. 테스트에 복사해 두면 WHERE를 바꿔도 복사본은 그대로라 통과한 채 인덱스만 놓친다.
   */
  String SNAPSHOT_UNFINISHED_SQL =
      """
          SELECT COUNT(*) FILTER (WHERE status = :pendingStatus) AS "pending",
                 COUNT(*) FILTER (WHERE status = :processingStatus) AS "processing",
                 GREATEST(COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(created_at)), 0), 0)
                     ::double precision AS "oldestUnfinishedAgeSeconds",
                 COUNT(*) FILTER (
                     WHERE status = :processingStatus AND lease_expires_at <= NOW()) AS "expiredLeases",
                 COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(lease_expires_at) FILTER (
                     WHERE status = :processingStatus AND lease_expires_at <= NOW())), 0)::double precision
                     AS "expiredLeaseMaxAgeSeconds",
                 COUNT(*) FILTER (WHERE read_deadline_at <= NOW()) AS "readDeadlineExceeded"
          FROM minsu_task_draft_generations
          WHERE status <> 'completed'
          """;

  Optional<TaskDraftGeneration> findByTaskId(UUID taskId);

  /** 적재 시점의 두 deadline은 원장과 같은 시계로 계산한다(8.6절). 읽기 투영도 같은 {@code NOW()}를 쓴다. */
  @Query(value = "SELECT NOW()", nativeQuery = true)
  Instant currentDatabaseTime();

  /**
   * 아직 생성이 진행 중인 원장이 있는지 판정한다(8.6절의 읽기 투영).
   *
   * <p>나이 상한을 DB에서 비교 하나로 끝내므로 scheduler가 멈춰 있어도, 설정을 꺼서 rollback해도 앱이 {@code generating}에 무기한 갇히지
   * 않는다. 상한을 scheduler가 강제하게 두면 scheduler가 멈춘 상황을 막으려는 장치를 그 scheduler가 실행하는 순환이 된다.
   *
   * <p>결과가 셋으로 갈린다. 빈 값은 미종료 원장이 없다는 뜻이고({@code ready}), {@code true}는 아직 창이 열려 있다는 뜻이며({@code
   * generating}), {@code false}는 deadline이 지나 투영이 닫는 경우다. 앞의 둘은 공개 계약에서 모두 {@code ready}지만 운영에서는
   * 구분해야 한다. deadline으로 닫힌 건수가 0이 아니면 생성이 제때 끝나지 않았다는 뜻이라 경보 대상이기 때문이다(9.3절). {@code EXISTS} 하나로는 이
   * 구분이 나오지 않는다.
   *
   * <p>{@code task_id}가 UNIQUE라 미종료 행은 많아야 하나다(8.4절).
   *
   * <p><b>이 3분기는 {@code read_deadline_at}이 NOT NULL이라는 데 기대고 있다.</b> nullable이 되면 행이 있는데도 빈 값이 돌아와
   * "원장 없음"과 구분되지 않고 조용히 {@code ready}가 된다. 컬럼 제약을 풀려면 이 판정부터 바꿔야 한다.
   */
  @Query(
      value =
          "SELECT read_deadline_at > NOW() FROM minsu_task_draft_generations "
              + "WHERE task_id = :taskId AND status <> 'completed'",
      nativeQuery = true)
  Optional<Boolean> generationWindowOpen(@Param("taskId") UUID taskId);

  /**
   * 재시도 시각이 지난 {@code pending} 원장을 claim 대상으로 잠근다(8.5절).
   *
   * <p>{@code FOR UPDATE SKIP LOCKED}로 여러 인스턴스가 같은 행을 집지 않게 한다({@code push_deliveries}와 같은 방식).
   * {@code (next_attempt_at) WHERE status = 'pending'} 부분 인덱스가 그대로 적중한다.
   *
   * <p>{@code apply_cutoff_at}이 지난 행은 제외한다. 반영 창이 이미 닫혀 결과를 쓸 수 없으므로 claim해도 provider 호출만 낭비된다. 그런
   * 행의 물리적 정리는 나중에 해도 되고, public 계약은 읽기 투영이 이미 닫았다(8.6절).
   */
  @Query(
      value =
          "SELECT * FROM minsu_task_draft_generations "
              + "WHERE status = 'pending' AND next_attempt_at <= NOW() AND apply_cutoff_at > NOW() "
              + "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<TaskDraftGeneration> lockDuePending(@Param("limit") int limit);

  /**
   * lease가 만료된 {@code processing} 원장을 회수 대상으로 잠근다(8.5절).
   *
   * <p>claim을 보유한 채 프로세스가 종료되면 이 경로로 돌아온다. 만료 판정은 DB 시계 기준이라 앱 서버 간 시계 차이의 영향을 받지 않는다. {@code
   * (lease_expires_at) WHERE status = 'processing'} 부분 인덱스가 적중한다.
   */
  @Query(
      value =
          "SELECT * FROM minsu_task_draft_generations "
              + "WHERE status = 'processing' AND lease_expires_at <= NOW() "
              + "AND apply_cutoff_at > NOW() "
              + "ORDER BY lease_expires_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<TaskDraftGeneration> lockExpiredProcessing(@Param("limit") int limit);

  /**
   * 원장 운영 지표를 한 번에 집계한다(9.3절).
   *
   * <p>여섯 값이 모두 미종료 행만 필요해 스캔을 {@code status <> 'completed'}로 좁힌다. 미종료 집합은 처리량에 묶여 유계이므로 테이블이 커져도
   * 비용이 늘지 않는다. {@code completed} 건수를 여기에 넣지 않는 이유가 이것이다. 하나만 넣어도 전체 스캔이 된다.
   *
   * <p>WHERE 절이 {@code idx_minsu_task_draft_generations_unfinished}의 부분 인덱스 술어와 <b>문자 그대로</b> 같아야
   * planner가 그 인덱스를 쓴다. 조건을 바꿀 때 마이그레이션도 함께 봐야 한다.
   *
   * <p>`COUNT FILTER`의 status 값은 호출하는 쪽에서 `GenerationStatus`의 값을 파라미터로 전달합니다. 부분 인덱스 사용 여부는 WHERE
   * 조건으로 결정되므로 이 값은 리터럴로 작성하지 않아도 됩니다.
   *
   * <p>{@code lease_expires_at}은 claim이 DB 시계로 계산해 저장한 값이라 여기서 {@code NOW()}와 빼는 것이 같은 시계 안의 연산이다.
   * 반면 <b>{@code created_at}은 JPA Auditing이 애플리케이션 시계로 쓴다.</b> 두 시계가 어긋나면 나이가 음수가 될 수 있어 {@code
   * GREATEST}로 0에서 자른다. 편차는 보통 밀리초 단위이고, 이 지표가 보려는 것은 정지로 인한 큰 양수라 0 절단이 판정을 가리지 않는다.
   *
   * <p>별칭을 큰따옴표로 감싼 것은 Postgres가 따옴표 없는 식별자를 소문자로 접기 때문이다. interface projection은 별칭과 속성명을 맞춰야 한다.
   */
  @Query(value = SNAPSHOT_UNFINISHED_SQL, nativeQuery = true)
  LedgerSnapshotRow snapshotUnfinished(
      @Param("pendingStatus") String pendingStatus,
      @Param("processingStatus") String processingStatus);

  /**
   * 반영 창이 닫혀 아무도 집을 수 없게 된 미종료 원장을 회수 대상으로 잠근다(11.1절).
   *
   * <p>두 claim 쿼리가 {@code apply_cutoff_at > NOW()}를 요구하므로 그 시각을 지난 행은 <b>영영 claim되지 않는다.</b> 종료 전이는
   * claim된 행에만 일어나므로 닫아 주는 경로가 없으면 미종료로 영구히 남고, 그러면 상태 gauge가 현재 적체와 버려진 행을 구분하지 못하며 {@link
   * #snapshotUnfinished}의 "미종료 집합은 유계"라는 전제도 깨진다.
   *
   * <p><b>진행 중인 시도와 겹치지 않도록 조건을 좁힌다.</b> lease가 살아 있는 {@code processing}은 지금 누군가 실행 중이고, 그 결과는
   * {@link TaskDraftGenerationLedger#record}의 cutoff 분기가 {@code deadline_exceeded}로 닫는다. 여기서 먼저 닫으면
   * claim token이 지워져 그 결과가 stale로 잘못 집계된다. 워커가 죽었으면 lease가 만료되므로 다음 주기에 이쪽으로 들어온다.
   *
   * <p>이 조건은 두 claim 쿼리와 <b>교집합이 없다.</b> 양쪽 모두 {@code apply_cutoff_at > NOW()}를 요구하므로 경합하지 않는다.
   */
  @Query(
      value =
          "SELECT * FROM minsu_task_draft_generations "
              + "WHERE status <> 'completed' AND apply_cutoff_at <= NOW() "
              + "AND (status = 'pending' OR lease_expires_at <= NOW()) "
              + "ORDER BY apply_cutoff_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<TaskDraftGeneration> lockAbandoned(@Param("limit") int limit);

  /** 결과 기록 트랜잭션이 대상 행을 잠근다. 여기서 잠가야 claim token 재검증과 전이가 원자적이다(8.2절). */
  @Query(
      value = "SELECT * FROM minsu_task_draft_generations WHERE id = :id FOR UPDATE",
      nativeQuery = true)
  Optional<TaskDraftGeneration> lockById(@Param("id") UUID id);
}
