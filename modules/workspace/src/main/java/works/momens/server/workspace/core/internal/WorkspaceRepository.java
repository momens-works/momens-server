package works.momens.server.workspace.core.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

  Optional<Workspace> findBySlug(String slug);

  boolean existsBySlug(String slug);

  /**
   * 전달받은 ID에 해당하는 워크스페이스를 생성 시각 내림차순으로 조회합니다. 정렬 기준은 레거시와 동일하며 {@code
   * docs/design/legacy-product-api-migration/slice-workspace-read.md} 4.3절을 따릅니다.
   *
   * <p>사용자가 어느 워크스페이스에 속하는지는 {@code membership} 하위 도메인이 관리하므로 이 조회에서는 {@code workspace_members}를
   * 참조하지 않습니다. 호출하는 쪽에서 멤버십 공개 계약을 통해 워크스페이스 ID를 확정한 뒤 전달합니다.
   */
  List<Workspace> findByIdInOrderByCreatedAtDesc(Collection<UUID> workspaceIds);
}
