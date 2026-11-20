package works.momens.server.project.core.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectOwnerRepository extends JpaRepository<ProjectOwner, ProjectOwnerId> {

  /** 소유자를 프로젝트 수와 무관하게 한 번 조회합니다. 결과는 생성 시각·소유자 id 순서입니다. */
  List<ProjectOwner> findByProjectIdInOrderByCreatedAtAscOwnerUserIdAsc(
      Collection<UUID> projectIds);
}
