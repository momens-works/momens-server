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
   * {@link #snapshotUnfinished()}의 SQL. 상수로 뺀 이유는 테스트가 <b>이 문장 그대로</b> EXPLAIN해 부분 인덱스가 실제로 쓰이는지 보기
   * 위해서다. 테스트에 복사해 두면 WHERE를 바꿔도 복사본은 그대로라 통과한 채 인덱스만 놓친다.
   */
  String SNAPSHOT_UNFINISHED_SQL =
      """
          SELECT COUNT(*) FILTER (WHERE status = 'pending') AS "pending",
                 COUNT(*) FILTER (WHERE status = 'processing') AS "processing",
                 GREATEST(COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(created_at)), 0), 0)
                     ::double precision AS "oldestUnfinishedAgeSeconds",
                 COUNT(*) FILTER (
                     WHERE status = 'processing' AND lease_expires_at <= NOW()) AS "expiredLeases",
                 COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(lease_expires_at) FILTER (
                     WHERE status = 'processing' AND lease_expires_at <= NOW())), 0)::double precision
                     AS "expiredLeaseMaxAgeSeconds",
                 COUNT(*) FILTER (WHERE read_deadline_at <= NOW()) AS "readDeadlineExceeded"
          FROM minsu_task_draft_generations
          WHERE status <> 'completed'
          """;

  Optional<TaskDraftGeneration> findByTaskId(UUID taskId);

  /** 적재 시점의 두 deadline은 원장과 같은 시계로 계산한다. 읽기 투영도 같은 {@code NOW()}를 쓴다. */
  @Query(value = "SELECT NOW()", nativeQuery = true)
  Instant currentDatabaseTime();

  /**
   * 생성 진행 여부를 DB 시계와 저장된 deadline으로 판정한다. scheduler가 멈추거나 비활성화되어도 읽기 투영은 deadline에 닫힌다.
   *
   * <p>빈 값(미종료 원장 없음)과 {@code false}(deadline 경과)는 공개 계약에서 모두 {@code ready}다. {@code true}만 {@code
   * generating}이며, deadline 경과는 별도 운영 지표로 센다.
   *
   * <p>{@code task_id} UNIQUE로 대상은 최대 한 행이다. {@code read_deadline_at}의 NOT NULL 제약을 풀면 원장 없음과
   * deadline 누락을 구분할 수 없으므로 이 판정도 함께 바꿔야 한다.
   */
  @Query(
      value =
          "SELECT read_deadline_at > NOW() FROM minsu_task_draft_generations "
              + "WHERE task_id = :taskId AND status <> 'completed'",
      nativeQuery = true)
  Optional<Boolean> generationWindowOpen(@Param("taskId") UUID taskId);

  /**
   * 재시도 시각이 지난 {@code pending} 원장을 claim 대상으로 잠근다.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED}로 여러 인스턴스가 같은 행을 집지 않게 한다({@code push_deliveries}와 같은 방식).
   * {@code (next_attempt_at) WHERE status = 'pending'} 부분 인덱스가 그대로 적중한다.
   *
   * <p>{@code apply_cutoff_at}이 지난 행은 제외한다. 반영 창이 이미 닫혀 결과를 쓸 수 없으므로 claim해도 provider 호출만 낭비된다. 그런
   * 행의 물리적 정리는 나중에 해도 되고, public 계약은 읽기 투영이 이미 닫았다.
   */
  @Query(
      value =
          "SELECT * FROM minsu_task_draft_generations "
              + "WHERE status = 'pending' AND next_attempt_at <= NOW() AND apply_cutoff_at > NOW() "
              + "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<TaskDraftGeneration> lockDuePending(@Param("limit") int limit);

  /**
   * lease가 만료된 {@code processing} 원장을 회수 대상으로 잠근다.
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
   * 미종료 원장만 집계해 완료 이력 증가가 조회 범위를 넓히지 않게 한다. {@code status <> 'completed'} 조건은 미종료 부분 인덱스와 맞춰 유지한다.
   *
   * <p>{@code lease_expires_at}과 {@code NOW()}는 DB 시계지만 {@code created_at}은 JPA Auditing의 앱 시계다. 시계
   * 차이로 음수가 된 나이는 {@code GREATEST}로 0에서 자른다.
   *
   * <p>PostgreSQL의 식별자 소문자 변환을 피하고 interface projection 속성과 맞추기 위해 별칭을 큰따옴표로 감싼다.
   */
  @Query(value = SNAPSHOT_UNFINISHED_SQL, nativeQuery = true)
  LedgerSnapshotRow snapshotUnfinished();

  /**
   * 반영 창이 닫혀 아무도 집을 수 없게 된 미종료 원장을 회수 대상으로 잠근다.
   *
   * <p>두 claim 쿼리가 {@code apply_cutoff_at > NOW()}를 요구하므로 그 시각을 지난 행은 <b>영영 claim되지 않는다.</b> 종료 전이는
   * claim된 행에만 일어나므로 닫아 주는 경로가 없으면 미종료로 영구히 남고, 그러면 상태 gauge가 현재 적체와 버려진 행을 구분하지 못하며 {@link
   * #snapshotUnfinished()}의 "미종료 집합은 유계"라는 전제도 깨진다.
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

  /** 결과 기록 트랜잭션이 대상 행을 잠근다. 여기서 잠가야 claim token 재검증과 전이가 원자적이다. */
  @Query(
      value = "SELECT * FROM minsu_task_draft_generations WHERE id = :id FOR UPDATE",
      nativeQuery = true)
  Optional<TaskDraftGeneration> lockById(@Param("id") UUID id);
}
