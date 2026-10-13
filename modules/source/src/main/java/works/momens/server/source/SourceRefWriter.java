package works.momens.server.source;

import java.util.UUID;

/**
 * {@code source_refs} 쓰기 public API입니다.
 *
 * <p>사용자가 태스크에 붙여넣은 링크를 {@code source_ref} 한 건으로 저장합니다. 레거시 {@code relation/service.go}의 {@code
 * CreateAndLinkTaskSourceRef}가 생성하던 행과 같은 값을 저장합니다.
 *
 * <p>{@code source_connection_id}가 없는 행을 생성합니다. provider 연동으로 수집한 원본이 아니라 사용자가 주소를 직접 입력한 원본이기
 * 때문입니다. {@code content_hash}도 저장하지 않습니다. 해당 값을 저장하면 같은 주소를 두 번 연결할 때 부분 UNIQUE 인덱스가 두 번째 행의 생성을
 * 막지만, 레거시는 요청마다 새 행을 생성합니다.
 *
 * <p>dev 전용인 {@code DevSourceRefWriter}와는 별개입니다. 해당 API는 Signal 데모에 사용할 근거 원본을 저장하며, 운영 환경에는 빈이
 * 등록되지 않습니다.
 */
public interface SourceRefWriter {

  /**
   * 사용자가 붙여넣은 주소로 {@code source_ref} 한 건을 저장하고 생성된 식별자를 반환합니다. 호출자의 트랜잭션에 참여합니다.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException 호출자 트랜잭션 없이 실행한 경우
   */
  UUID createManualLink(NewManualLink manualLink);

  record NewManualLink(UUID workspaceId, String sourceType, String sourceUrl, String title) {}
}
