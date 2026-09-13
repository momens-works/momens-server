package works.momens.server.workspace.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * workspace 모듈의 워크스페이스 조회 public API.
 *
 * <p>웹 표면(MOM-0851)이 workspace 내부 repository를 직접 참조하지 않고도 워크스페이스 정보를 읽을 수 있도록 합니다. workspace가 없을 때
 * 반환할 에러(WORKSPACE_NOT_FOUND 등)와 멤버십 판정은 호출하는 쪽이 결정합니다({@link WorkspaceMembershipReader#roleOf}
 * 참고).
 */
public interface WorkspaceReader {

  /** workspaceId 한 건을 조회합니다. */
  Optional<WorkspaceDetail> findById(UUID workspaceId);

  /**
   * userId가 멤버인 워크스페이스를 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다.
   *
   * <p>별도 권한 검사가 필요 없습니다. 멤버십 조인 자체가 접근 범위 필터입니다.
   */
  List<WorkspaceDetail> listByMemberUserId(UUID userId);
}
