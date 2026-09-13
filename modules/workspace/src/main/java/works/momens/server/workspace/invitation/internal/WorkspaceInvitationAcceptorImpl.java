package works.momens.server.workspace.invitation.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.invitation.AcceptInvitationCommand;
import works.momens.server.workspace.invitation.AcceptedInvitation;
import works.momens.server.workspace.invitation.InvitationErrorCode;
import works.momens.server.workspace.invitation.InvitationStatus;
import works.momens.server.workspace.invitation.WorkspaceInvitationAcceptor;
import works.momens.server.workspace.membership.AddMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 초대 수락 기능을 구현합니다.
 *
 * <p>같은 토큰으로 동시에 들어온 요청이 모두 검증을 통과하지 않도록 초대 행에 lock을 걸고 조회합니다. 검증 순서는 레거시와 동일하게 상태, 만료 여부, 이메일 일치
 * 여부 순입니다. 이 순서가 달라지면 같은 요청에 대해 레거시와 신규 서버가 서로 다른 HTTP status를 반환할 수 있습니다.
 */
class WorkspaceInvitationAcceptorImpl implements WorkspaceInvitationAcceptor {

  private final WorkspaceInvitationRepository repository;
  private final WorkspaceMembershipWriter membershipWriter;
  private final WorkspaceReader workspaceReader;
  private final UserService userService;
  private final Clock clock;

  WorkspaceInvitationAcceptorImpl(
      WorkspaceInvitationRepository repository,
      WorkspaceMembershipWriter membershipWriter,
      WorkspaceReader workspaceReader,
      UserService userService,
      Clock clock) {
    this.repository = repository;
    this.membershipWriter = membershipWriter;
    this.workspaceReader = workspaceReader;
    this.userService = userService;
    this.clock = clock;
  }

  @Override
  @Transactional
  public AcceptedInvitation accept(AcceptInvitationCommand command) {
    String rawToken = command.rawToken() == null ? "" : command.rawToken().trim();
    if (rawToken.isEmpty()) {
      throw new BusinessException(InvitationErrorCode.INVITATION_INVALID_TOKEN, Map.of());
    }

    UserProfile user = userService.getProfile(command.userId());
    WorkspaceInvitation invitation =
        repository
            .findByTokenHash(InvitationToken.hash(rawToken))
            .orElseThrow(
                () -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND, Map.of()));

    Instant now = clock.instant();
    requireAcceptable(invitation, user.email(), now);

    WorkspaceRole role =
        WorkspaceRole.from(invitation.getRole())
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_INVALID_ROLE,
                        Map.of("role", invitation.getRole())));
    invitation.markAccepted(now);
    repository.flush();

    WorkspaceMembershipDetail membership =
        membershipWriter
            .addIfAbsent(new AddMembershipCommand(invitation.getWorkspaceId(), user.id(), role))
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_MEMBER_ALREADY_EXISTS,
                        Map.of("user_id", user.id().toString())));

    WorkspaceDetail workspace =
        workspaceReader
            .findById(invitation.getWorkspaceId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        Map.of("workspace_id", invitation.getWorkspaceId().toString())));
    return new AcceptedInvitation(workspace, membership);
  }

  private static void requireAcceptable(
      WorkspaceInvitation invitation, String userEmail, Instant now) {
    InvitationStatus stored =
        InvitationStatus.from(invitation.getStatus())
            .orElseThrow(
                () ->
                    new BusinessException(InvitationErrorCode.INVITATION_INVALID_TOKEN, Map.of()));
    switch (stored) {
      case PENDING -> {}
      case ACCEPTED ->
          throw new BusinessException(InvitationErrorCode.INVITATION_ALREADY_ACCEPTED, Map.of());
      case REVOKED -> throw new BusinessException(InvitationErrorCode.INVITATION_REVOKED, Map.of());
      default ->
          throw new BusinessException(InvitationErrorCode.INVITATION_INVALID_TOKEN, Map.of());
    }
    if (!now.isBefore(invitation.getExpiresAt())) {
      throw new BusinessException(InvitationErrorCode.INVITATION_EXPIRED, Map.of());
    }
    if (!normalizeEmail(userEmail).equals(invitation.getEmail())) {
      throw new BusinessException(InvitationErrorCode.INVITATION_EMAIL_MISMATCH, Map.of());
    }
  }

  private static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }
}
