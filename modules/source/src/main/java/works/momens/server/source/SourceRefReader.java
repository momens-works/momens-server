package works.momens.server.source;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 워크스페이스 범위의 활성 source_ref를 조회합니다. 다른 워크스페이스의 id와 소프트 삭제된 행은 결과에서 생략합니다.
 *
 * <p>반환 순서는 보장하지 않으므로 표시 순서는 호출자가 정합니다.
 */
public interface SourceRefReader {

  /** workspaceId에 속하고 ids에 해당하는, 소프트 삭제되지 않은 source_ref를 조회합니다. 없거나 다른 워크스페이스의 id는 결과에서 빠집니다. */
  List<SourceRefView> findByIds(UUID workspaceId, Collection<UUID> ids);

  List<LegacySourceRefDetail> findLegacyDetailsByIds(UUID workspaceId, Collection<UUID> ids);

  /**
   * source-ref가 속한 워크스페이스의 식별자를 조회합니다.
   *
   * <p>소프트 삭제된 source-ref는 존재하지 않는 것으로 처리해 빈 값을 반환합니다. 검증 요청의 권한을 확인하려면 대상이 속한 워크스페이스를 먼저 알아야 하므로 이
   * 조회 기능을 public API로 제공합니다.
   */
  Optional<UUID> findWorkspaceId(UUID sourceRefId);
}
