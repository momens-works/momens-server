package works.momens.server.context;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 워크스페이스 안의 활성 연결을 조회합니다.
 *
 * <p>연결이 살아 있어도 원본 source_ref는 삭제됐을 수 있습니다. 호출자는 {@code SourceRefReader}로 원본을 확인하고 같은 결과로 관련자료 목록과
 * 개수를 만듭니다.
 */
public interface EntityRelationReader {

  /**
   * N+1을 피하기 위해 여러 태스크를 한 번에 조회하며, 연결이 없는 태스크는 결과 map에서 생략합니다.
   *
   * <p>목록은 링크 생성 시각 내림차순, 링크 id 내림차순입니다. nullable한 source 생성 시각 대신 링크 기준으로 정렬해 Signal evidence와 표시
   * 순서를 맞춥니다.
   */
  Map<UUID, List<UUID>> findLinkedSourceRefIds(UUID workspaceId, Collection<UUID> taskIds);

  /** 여러 태스크의 memory·source ref 연결을 한 번에 조회합니다. 연결이 없는 태스크는 결과에 포함하지 않습니다. */
  Map<UUID, TaskContextLinks> findTaskContextLinks(UUID workspaceId, Collection<UUID> taskIds);

  TaskContextLinks findTaskContextLinks(UUID workspaceId, UUID taskId);
}
