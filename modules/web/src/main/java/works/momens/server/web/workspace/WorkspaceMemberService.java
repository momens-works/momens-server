package works.momens.server.web.workspace;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;
import works.momens.server.workspace.membership.ChangeMembershipRoleCommand;
import works.momens.server.workspace.membership.RemoveMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 목록 조합은 {@link WorkspaceMemberListService}, 인가는 {@code WorkspaceAccessChecker}, owner 보호·자기 제거 금지는
 * workspace 모듈에 위임합니다.
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

  @Transactional
  public void changeRole(
      UUID workspaceId, UUID userId, UUID targetUserId, AssignableWorkspaceRole role) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    workspaceMembershipWriter.changeRole(
        new ChangeMembershipRoleCommand(workspaceId, targetUserId, role));
  }

  @Transactional
  public void remove(UUID workspaceId, UUID userId, UUID targetUserId) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    workspaceMembershipWriter.remove(
        new RemoveMembershipCommand(workspaceId, userId, targetUserId));
  }
}
