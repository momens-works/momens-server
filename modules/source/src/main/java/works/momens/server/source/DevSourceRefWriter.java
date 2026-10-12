package works.momens.server.source;

import java.time.Instant;
import java.util.UUID;

/**
 * dev 전용 source_refs 쓰기 public API.
 *
 * <p>{@code source_refs}는 운영에서 레거시 momens-api가 소유하는 외부 테이블이라 이 서버는 읽기만 한다(ADR-0007·ADR-0008). dev
 * Signal 생성 데모가 evidence 원본을 완전하게 저장하기 위한 dev 한정 예외이며, 구현은 {@code @DevOnly}로 게이트되어 prod에는 빈 자체가
 * 등록되지 않는다. {@code @Immutable} 읽기 엔티티를 재사용하지 않는 전용 insert 경로이고, 호출자(dev Signal 생성) 트랜잭션에 합류한다.
 */
public interface DevSourceRefWriter {

  /**
   * source_ref 한 건을 insert하고 생성된 id를 돌려준다.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException 호출자 트랜잭션 없이 호출하면 실패한다.
   */
  UUID insert(NewSourceRef sourceRef);

  /** dev 생성 API가 받는 evidence 원본 필드. */
  record NewSourceRef(
      UUID workspaceId,
      String sourceType,
      String title,
      String snippet,
      String text,
      String sourceUrl,
      Instant sourceCreatedAt) {}
}
