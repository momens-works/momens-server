package works.momens.server.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * outbox consumer 조회 public API(ADR-0009).
 *
 * <p>{@code id}는 BIGSERIAL이라 id 발급 순서와 commit 순서가 다를 수 있다. 나중 id의 event가 먼저 commit되면 watermark가 아직
 * commit되지 않은 앞 id를 지나쳐 그 event를 영구히 건너뛴다. 이를 막기 위해 {@code readAfter}는 안전 지연을 아직 지나지 않은 첫 event에서
 * 스캔을 멈춘다(prefix-cap). 생산자 트랜잭션이 안전 지연 안에 종료된다는 전제에서는 commit된 event가 최대 안전 지연만큼 늦어질 뿐 건너뛰어지지 않는다.
 * 안전 지연보다 오래 열린 생산자 트랜잭션은 이 보장 범위 밖이다. 기준 시각은 생산자와 같은 DB 시계({@code NOW()})라 앱 서버 시계 차이의 영향을 받지 않는다.
 * 소비 상태(watermark)는 consumer가 자기 모듈에서 관리하고, 이 모듈은 조회만 제공한다.
 */
public interface OutboxEventReader {

  /**
   * watermark({@code afterId}) 이후의 event를 id 오름차순으로 읽되, 생성된 지 {@code safetyLag}가 지나지 않은 첫 event에서
   * 멈춘다. 생산자 트랜잭션이 {@code safetyLag} 안에 종료된다는 전제에서 반환 목록의 마지막 id까지 watermark를 전진시켜도 event를 건너뛰지
   * 않는다.
   */
  List<OutboxEventView> readAfter(long afterId, Duration safetyLag, int limit);

  /** 안전 지연을 지난 연속 prefix의 마지막 id. 없으면 0(consumer 최초 watermark 시드용). */
  long latestIdBefore(Duration safetyLag);

  /** id 한 건 조회. 발송 시점에 event의 {@code aggregateId}로 본문을 hydrate할 때 사용한다. */
  Optional<OutboxEventView> findById(long id);
}
