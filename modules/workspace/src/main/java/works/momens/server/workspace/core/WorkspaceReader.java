package works.momens.server.workspace.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * workspace 모듈의 워크스페이스 조회 public API.
 *
 * <p>웹 표면(MOM-0851)이 workspace 내부 repository를 직접 참조하지 않고도 워크스페이스 정보를 읽을 수 있도록 합니다. workspace가 없을 때
 * 반환할 에러(WORKSPACE_NOT_FOUND 등)와 멤버십 판정은 호출하는 쪽이 결정합니다({@link WorkspaceMembershipReader#roleOf}
 * 참고).
 */
public interface WorkspaceReader {

  Optional<WorkspaceDetail> findById(UUID workspaceId);

  /** 사용자의 멤버십으로 접근 범위를 제한하며 워크스페이스 생성 시각 내림차순으로 반환합니다. */
  List<WorkspaceDetail> listByMemberUserId(UUID userId);
}
