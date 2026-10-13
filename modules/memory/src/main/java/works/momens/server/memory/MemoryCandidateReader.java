package works.momens.server.memory;

import java.util.List;
import java.util.UUID;

/** 상태 필터 없이 워크스페이스의 후보를 조회합니다. 상태별 표시는 호출하는 클라이언트가 결정합니다. */
public interface MemoryCandidateReader {

  /**
   * workspaceId의 후보를 모두 조회합니다.
   *
   * <p>정렬은 레거시와 같은 {@code importance} 내림차순이며, 값이 없는 후보가 뒤로 갑니다. 같은 중요도에서는 생성 시각 내림차순입니다.
   *
   * <p>상태로 거르지 않습니다. {@code REJECTED}·{@code EXPIRED} 후보도 함께 담깁니다.
   */
  List<MemoryCandidateDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
