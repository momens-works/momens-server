package works.momens.server.signal.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SignalRepository extends JpaRepository<Signal, UUID> {

  // action 경로(findLive)는 소프트 삭제만 제외하고 처리 여부는 보지 않는다. 처리된 Signal에 다른 action을
  // 요청하면 SIGNAL_INVALID_STATE로 거르는 판단은 action 서비스가 하기 때문이다.
  Optional<Signal> findByIdAndDeletedAtIsNull(UUID id);

  // 상세 조회는 미처리(signal_actions에 행 없음) Signal만 대상으로 한다. 처리된 Signal을 다시 보는 inbox는
  // MVP 이후라, 소프트 삭제와 마찬가지로 처리된 Signal도 없는 것으로 취급한다.
  @Query(
      value =
          "SELECT s.* FROM signals s WHERE s.id = :id AND s.deleted_at IS NULL"
              + " AND NOT EXISTS (SELECT 1 FROM signal_actions a WHERE a.signal_id = s.id)",
      nativeQuery = true)
  Optional<Signal> findUnprocessedById(@Param("id") UUID id);

  // 미처리 = signal_actions에 행이 없는 Signal(원장 존재 여부만 확인, MOM-65가 쓴다). 정렬은 생성
  // 시각 내림차순이고, worker가 evidence와 배치로 같이 insert해(D1) 동률이 날 수 있어 id 내림차순으로
  // 순서를 고정한다.
  @Query(
      value =
          "SELECT s.* FROM signals s WHERE s.project_id = :projectId AND s.deleted_at IS NULL"
              + " AND NOT EXISTS (SELECT 1 FROM signal_actions a WHERE a.signal_id = s.id)"
              + " ORDER BY s.created_at DESC, s.id DESC",
      nativeQuery = true)
  List<Signal> findUnprocessedByProjectId(@Param("projectId") UUID projectId);

  // 브리프의 당일 집계용. signal_actions와 무관하게 created_at이 [from, toExclusive) 범위인 Signal을
  // 조회한다(MOM-81). 전환이나 dismiss 여부와 상관없이 그날 온 시그널을 세고, 소프트 삭제는 제외한다.
  // 정렬과 id 보조 키는 미처리 조회와 같고, idx_signals_project_live 부분 인덱스를 탄다.
  @Query(
      value =
          "SELECT s.* FROM signals s WHERE s.project_id = :projectId AND s.deleted_at IS NULL"
              + " AND s.created_at >= :from AND s.created_at < :toExclusive"
              + " ORDER BY s.created_at DESC, s.id DESC",
      nativeQuery = true)
  List<Signal> findByProjectIdAndCreatedRange(
      @Param("projectId") UUID projectId,
      @Param("from") Instant from,
      @Param("toExclusive") Instant toExclusive);
}
