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
