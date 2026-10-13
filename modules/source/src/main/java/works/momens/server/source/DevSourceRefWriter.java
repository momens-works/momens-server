package works.momens.server.source;

import java.time.Instant;
import java.util.UUID;

/** dev Signal의 근거 원본을 저장하는 전용 경로입니다. {@code @DevOnly}로 prod에서 제외하고 호출자의 트랜잭션에 참여합니다. */
public interface DevSourceRefWriter {

  /**
   * source_ref 한 건을 insert하고 생성된 id를 돌려준다.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException 호출자 트랜잭션 없이 호출하면 실패한다.
   */
  UUID insert(NewSourceRef sourceRef);

  record NewSourceRef(
      UUID workspaceId,
      String sourceType,
      String title,
      String snippet,
      String text,
      String sourceUrl,
      Instant sourceCreatedAt) {}
}
