package works.momens.server.web.workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import works.momens.server.common.api.BusinessException;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.invitation.CreateInvitationCommand;
import works.momens.server.workspace.invitation.ResendInvitationCommand;
import works.momens.server.workspace.invitation.RevokeInvitationCommand;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;
import works.momens.server.workspace.invitation.WorkspaceInvitationReader;
import works.momens.server.workspace.invitation.WorkspaceInvitationWriter;
import works.momens.server.workspace.membership.AddMembershipByEmailCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 초대 조회와 변경 작업을 조합하는 서비스입니다.
 *
 * <p>workspace 모듈이 제공하는 public API만 조합하며, 초대와 관련된 도메인 정책은 직접 소유하지 않습니다. 워크스페이스의 존재 여부와 요청자의 역할은 이
 * 서비스에서 확인합니다. 해당 이메일의 사용자가 이미 멤버인지, 초대가 이미 수락되었는지와 같은 초대 규칙은 workspace 모듈에서 검증합니다.
 *
 * <p>초대를 생성하지 않고 멤버를 바로 추가하는 요청도 처리합니다. 초대 API와 같은 경로에 있지만, 초대를 생성하지 않고 멤버십만 추가하므로 workspace 모듈의
 * 멤버십 쓰기 API로 전달합니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceInvitationService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final WorkspaceInvitationReader workspaceInvitationReader;
  private final WorkspaceInvitationWriter workspaceInvitationWriter;
  private final WorkspaceMembershipWriter workspaceMembershipWriter;

  List<WorkspaceInvitationDetail> list(UUID workspaceId, UUID userId) {
    requireAdmin(workspaceId, userId);
    return workspaceInvitationReader.listByWorkspaceId(workspaceId);
  }

  WorkspaceInvitationDetail create(UUID workspaceId, UUID userId, String email, String rawRole) {
    requireAdmin(workspaceId, userId);
    return workspaceInvitationWriter.create(
        new CreateInvitationCommand(workspaceId, userId, email, assignableRole(rawRole)));
  }

  WorkspaceInvitationDetail resend(UUID workspaceId, UUID userId, UUID invitationId) {
    requireAdmin(workspaceId, userId);
    return workspaceInvitationWriter.resend(
        new ResendInvitationCommand(workspaceId, invitationId, userId));
  }

  WorkspaceInvitationDetail revoke(UUID workspaceId, UUID userId, UUID invitationId) {
    requireAdmin(workspaceId, userId);
    return workspaceInvitationWriter.revoke(new RevokeInvitationCommand(workspaceId, invitationId));
  }

  void addMember(UUID workspaceId, UUID userId, String email, String rawRole) {
    requireAdmin(workspaceId, userId);
    workspaceMembershipWriter.addByEmail(
        new AddMembershipByEmailCommand(workspaceId, email, assignableRole(rawRole)));
  }

  private void requireAdmin(UUID workspaceId, UUID userId) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
  }

  private static WorkspaceRole assignableRole(String rawRole) {
    return WorkspaceRole.assignableFrom(rawRole)
        .orElseThrow(
            () ->
                new BusinessException(
                    WorkspaceErrorCode.WORKSPACE_INVALID_ROLE,
                    Map.of("role", String.valueOf(rawRole))));
  }
}
