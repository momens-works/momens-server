package works.momens.server.project.milestone;

import java.util.List;
import java.util.UUID;

/**
 * project 모듈의 마일스톤 조회 public API.
 *
 * <p>마일스톤은 {@code project_id}만 갖고 워크스페이스를 직접 알지 못하므로, 조회가 project를 거쳐 올라갑니다. 그래서 별도 모듈이 아니라 project
 * 모듈이 소유합니다(MOM-0856).
 *
 * <p>소프트 삭제된 마일스톤은 없는 것으로 취급하며, 소속 project가 소프트 삭제된 마일스톤도 마찬가지입니다.
 *
 * <p>프로젝트별 목록과 단건 조회는 두지 않습니다. snapshot 합성(MOM-0862)이 워크스페이스 단위 목록만 쓰고, 레거시 단건 endpoint(H056)는 웹
 * 클라이언트에 호출 코드가 아예 없으며, 프로젝트별 목록(H051)의 유일한 호출자는 snapshot이 없을 때만 타는 FE 폴백입니다. 필요해지면 그때 추가합니다.
 */
public interface MilestoneReader {

  /** workspaceId에 속한 project들의 마일스톤을 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다. */
  List<MilestoneDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
