package works.momens.server.project.core;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 소프트 삭제된 프로젝트는 제외합니다. {@link ProjectReader}보다 넓은 웹 조회 필드를 제공합니다. */
public interface ProjectDetailReader {

  /** workspaceId에 속한 project를 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<ProjectDetail> listDetailsByWorkspaceId(UUID workspaceId);

  /** 생성 시각·id 내림차순으로 cursor 이후 최대 limit개를 DB에서 조회합니다. */
  List<ProjectDetail> listPage(UUID workspaceId, Instant before, UUID beforeId, int limit);

  /** 요청한 id에 한정하고 workspace와 소프트 삭제 조건을 적용합니다. */
  List<ProjectDetail> listByIds(UUID workspaceId, Collection<UUID> ids);

  /** 이름은 100행씩 Java 문자열 의미로 판정하고 최대 두 후보를 반환합니다. 총 조회량은 프로젝트 수에 비례합니다. */
  List<ProjectDetail> findReferenceCandidates(UUID workspaceId, UUID referenceId, String reference);
}
