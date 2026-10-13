package works.momens.server.project.milestone;

import java.util.List;
import java.util.UUID;

/** 마일스톤과 소속 프로젝트 중 하나라도 소프트 삭제됐으면 조회에서 제외합니다. */
public interface MilestoneReader {

  /** workspaceId에 속한 project들의 마일스톤을 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<MilestoneDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
