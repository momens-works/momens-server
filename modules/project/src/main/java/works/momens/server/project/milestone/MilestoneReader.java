package works.momens.server.project.milestone;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 소프트 삭제된 마일스톤은 조회에서 제외합니다.
 *
 * <p>{@code milestones}에는 workspace id가 없으므로 워크스페이스 범위와 소속 프로젝트의 삭제 여부는 호출자가 정합니다. 호출자는 같은 워크스페이스의
 * 삭제되지 않은 project id만 넘겨야 하며, 빈 목록이면 조회 없이 빈 결과를 반환합니다.
 */
public interface MilestoneReader {

  /** projectIds에 속한 마일스톤을 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<MilestoneDetail> listDetailsByProjectIds(Collection<UUID> projectIds);

  /** 생성 시각·id 내림차순으로 cursor 이후 최대 limit개를 DB에서 조회합니다. */
  List<MilestoneDetail> listPage(
      Collection<UUID> projectIds, Instant before, UUID beforeId, int limit);

  /** 요청한 id 중 projectIds에 속한 마일스톤만 조회합니다. */
  List<MilestoneDetail> listByIds(Collection<UUID> projectIds, Collection<UUID> ids);
}
