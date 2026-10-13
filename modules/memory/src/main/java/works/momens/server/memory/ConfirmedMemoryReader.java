package works.momens.server.memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 워크스페이스 목록과 태스크 context 조합에 필요한 확정 메모리를 조회합니다. */
public interface ConfirmedMemoryReader {

  /**
   * workspaceId의 확정 메모리를 모두 조회합니다.
   *
   * <p>정렬은 레거시와 같은 생성 시각 내림차순입니다. 레거시에 tie-break가 없어 두지 않습니다.
   *
   * <p>소프트 삭제된 메모리는 없는 것으로 취급합니다. 상태로는 거르지 않아 {@code INVALIDATED}·{@code ARCHIVED} 메모리도 함께 담깁니다.
   */
  List<ConfirmedMemoryDetail> listDetailsByWorkspaceId(UUID workspaceId);

  List<ConfirmedMemoryDetail> findDetailsByIds(UUID workspaceId, Collection<UUID> ids);

  /**
   * 메모리가 속한 워크스페이스의 식별자를 조회합니다.
   *
   * <p>소프트 삭제되었거나 존재하지 않는 메모리는 빈 값을 반환합니다. 태스크에 메모리를 연결하려면 두 대상이 같은 워크스페이스에 속하는지 먼저 확인해야 하므로 해당 조회
   * 기능을 public API로 제공합니다. {@code SourceRefReader}의 같은 이름을 가진 메서드와 동일하게 동작합니다.
   */
  Optional<UUID> findWorkspaceId(UUID memoryId);
}
