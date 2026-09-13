package works.momens.server.web.workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.membership.ChangeMembershipRoleCommand;
import works.momens.server.workspace.membership.RemoveMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 멤버 조회와 변경을 조합하는 서비스입니다. 조회는 {@link WorkspaceMemberListService}에 위임하고, 변경은 {@code
 * workspace} 모듈의 public API를 호출합니다. 도메인 정책은 소유하지 않습니다.
 *
 * <p>워크스페이스 존재 여부와 요청자의 역할은 이 서비스에서 확인하고, owner 보호와 자기 제거 금지 등 멤버십 도메인 규칙은 workspace 모듈에서 확인합니다.
 * 워크스페이스 존재 여부를 역할보다 먼저 확인하는 이유는 순서를 바꾸면 존재하지 않는 워크스페이스에도 404가 아닌 403을 반환하기 때문입니다.
 *
 * <p>{@code requireWorkspaceExists}와 {@code requireRoleAtLeast}는 {@link WorkspaceService}에도 같은 형태로
 * 존재합니다. 세 번째 중복이 발생하는 시점에 공통 위치로 옮기기로 했습니다(PR #159 리뷰).
 */
@Service
@RequiredArgsConstructor
class WorkspaceMemberService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final WorkspaceMemberListService workspaceMemberListService;
  private final WorkspaceMembershipWriter workspaceMembershipWriter;

  /**
   * 워크스페이스 존재 여부를 확인한 뒤 멤버 목록 조회를 {@link WorkspaceMemberListService}에 위임합니다. 정렬 기준과 사용자 정보 결합은 해당
   * 서비스가 담당하므로, 해당 메서드는 존재하지 않는 워크스페이스에 404를 먼저 반환하는 판정 순서만 관리합니다.
   */
  @Transactional(readOnly = true)
  public List<WorkspaceMemberView> list(UUID workspaceId, UUID userId) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    return workspaceMemberListService.list(workspaceId, userId);
  }

  /** 멤버의 역할을 변경합니다. 부여할 수 있는 역할인지는 enum이 판정하며, 판정 결과가 없으면 {@code WORKSPACE_INVALID_ROLE}을 던집니다. */
  @Transactional
  public void changeRole(UUID workspaceId, UUID userId, UUID targetUserId, String rawRole) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    WorkspaceRole role =
        WorkspaceRole.assignableFrom(rawRole)
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_INVALID_ROLE,
                        Map.of("role", String.valueOf(rawRole))));
    workspaceMembershipWriter.changeRole(
        new ChangeMembershipRoleCommand(workspaceId, targetUserId, role));
  }

  /** 멤버를 제거합니다. 요청자 ID를 그대로 전달해 workspace 모듈에서 자기 제거 요청인지 판정하도록 합니다. */
  @Transactional
  public void remove(UUID workspaceId, UUID userId, UUID targetUserId) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    workspaceMembershipWriter.remove(
        new RemoveMembershipCommand(workspaceId, userId, targetUserId));
  }
}
