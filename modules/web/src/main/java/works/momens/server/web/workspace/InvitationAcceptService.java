package works.momens.server.web.workspace;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.web.workspace.dto.response.AcceptInvitationResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceMemberResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceResponse;
import works.momens.server.workspace.invitation.AcceptInvitationCommand;
import works.momens.server.workspace.invitation.AcceptedInvitation;
import works.momens.server.workspace.invitation.WorkspaceInvitationAcceptor;

/**
 * 초대 수락 결과를 응답 형식에 맞게 조합하는 서비스입니다.
 *
 * <p>workspace 모듈에서는 멤버십의 역할과 생성 시각만 반환하므로, 사용자 이름과 이메일은 user 모듈에서 조회해 조합합니다. 멤버 목록 응답과 동일한 방식으로
 * 구성합니다.
 */
@Service
@RequiredArgsConstructor
class InvitationAcceptService {

  private final WorkspaceInvitationAcceptor workspaceInvitationAcceptor;
  private final UserService userService;

  AcceptInvitationResponse accept(UUID userId, String token) {
    AcceptedInvitation accepted =
        workspaceInvitationAcceptor.accept(new AcceptInvitationCommand(userId, token));
    UserProfile profile = userService.getProfile(userId);
    WorkspaceMemberView view =
        new WorkspaceMemberView(
            profile.id(),
            profile.email(),
            profile.name(),
            accepted.membership().role(),
            accepted.membership().createdAt(),
            accepted.membership().updatedAt());
    return new AcceptInvitationResponse(
        WorkspaceResponse.from(accepted.workspace()), WorkspaceMemberResponse.from(view));
  }
}
