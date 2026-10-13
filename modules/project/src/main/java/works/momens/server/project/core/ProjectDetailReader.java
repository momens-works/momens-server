package works.momens.server.project.core;

import java.util.List;
import java.util.UUID;

/** 소프트 삭제된 프로젝트는 제외합니다. {@link ProjectReader}보다 넓은 웹 조회 필드를 제공합니다. */
public interface ProjectDetailReader {

  /** workspaceId에 속한 project를 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<ProjectDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
