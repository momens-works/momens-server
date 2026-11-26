package works.momens.server.memory;

import java.util.UUID;

/**
 * 웹 Product API가 사용하는 memory write public API입니다.
 *
 * <p>레거시 {@code momens-api}의 후보 리뷰(H084~H088)와 메모리 해결(H093)을 그대로 옮깁니다. 권한은 모두 workspace member이고,
 * 후보는 {@code PROPOSED}일 때만 리뷰할 수 있습니다.
 *
 * <p>{@link #confirm}과 {@link #editAndConfirm}은 레거시에서 같은 서비스 함수의 두 진입점이지만, 남기는 review action이
 * {@code CONFIRM}과 {@code EDIT_AND_CONFIRM}으로 갈립니다. 그 차이가 호출부의 인자 유무가 아니라 계약에 드러나도록 메서드를 나눕니다.
 */
public interface MemoryWriter {

  /** 후보를 편집 없이 확정합니다. review action은 {@code CONFIRM}입니다. */
  ConfirmedMemoryDetail confirm(UUID candidateId, UUID userId);

  /**
   * 후보를 편집해 확정합니다. review action은 {@code EDIT_AND_CONFIRM}입니다.
   *
   * <p>레거시와 같이 비어 있지 않은 필드만 후보 값을 덮어씁니다. 모든 필드가 비어 있어도 편집 요청 자체는 유효하며 action type은 그대로 {@code
   * EDIT_AND_CONFIRM}입니다.
   */
  ConfirmedMemoryDetail editAndConfirm(UUID candidateId, UUID userId, CandidateEdits edits);

  void reject(UUID candidateId, UUID userId, String reason);

  void merge(UUID candidateId, UUID targetMemoryId, UUID userId);

  void expire(UUID candidateId, UUID userId);

  /**
   * {@code resolvingMemoryId}가 {@code resolvedMemoryId}를 해결합니다. 관계를 남기고 해결된 쪽을 보관 처리합니다.
   *
   * <p>두 메모리 모두 {@code ACTIVE}여야 합니다. 같은 쌍으로 이미 해결된 요청은 아무것도 바꾸지 않고 성공으로 끝납니다.
   */
  void resolve(UUID resolvedMemoryId, UUID resolvingMemoryId, UUID userId);

  record CandidateEdits(String title, String summary, String body) {}
}
