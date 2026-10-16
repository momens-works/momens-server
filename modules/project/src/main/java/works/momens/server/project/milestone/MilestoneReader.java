package works.momens.server.project.milestone;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 마일스톤과 소속 프로젝트 중 하나라도 소프트 삭제됐으면 조회에서 제외합니다. */
public interface MilestoneReader {

  /** workspaceId에 속한 project들의 마일스톤을 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<MilestoneDetail> listDetailsByWorkspaceId(UUID workspaceId);

  /** 생성 시각·id 내림차순으로 cursor 이후 최대 limit개를 DB에서 조회합니다. */
  List<MilestoneDetail> listPage(
      UUID workspaceId, UUID projectId, Instant before, UUID beforeId, int limit);

  /** 요청한 id에 한정하고 workspace와 소프트 삭제 조건을 적용합니다. */
  List<MilestoneDetail> listByIds(UUID workspaceId, Collection<UUID> ids);
}
