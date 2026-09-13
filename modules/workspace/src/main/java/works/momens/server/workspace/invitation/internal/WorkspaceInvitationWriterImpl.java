package works.momens.server.workspace.invitation.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.email.EmailSendFailedException;
import works.momens.server.workspace.email.InvitationEmail;
import works.momens.server.workspace.email.InvitationEmailSender;
import works.momens.server.workspace.invitation.CreateInvitationCommand;
import works.momens.server.workspace.invitation.InvitationErrorCode;
import works.momens.server.workspace.invitation.ResendInvitationCommand;
import works.momens.server.workspace.invitation.RevokeInvitationCommand;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;
import works.momens.server.workspace.invitation.WorkspaceInvitationWriter;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 워크스페이스 초대 쓰기 기능을 구현합니다.
 *
 * <p>메서드 전체를 하나의 트랜잭션으로 묶지 않습니다. 초대 생성과 재발송은 초대 정보를 저장한 뒤 외부 이메일 서비스를 호출하고, 호출 결과에 따라 다시 저장합니다. 전체
 * 흐름을 하나의 트랜잭션으로 묶으면 외부 서비스의 응답을 기다리는 동안 DB 커넥션을 점유하게 되므로 각 저장 구간만 별도의 트랜잭션으로 처리합니다.
 *
 * <p>재발송과 폐기는 수락되지 않은 초대에만 반영되도록 조건을 포함한 단일 UPDATE 쿼리로 처리합니다. 상태를 먼저 조회한 뒤 Java에서 판정하면 조회와 갱신 사이에
 * 초대 수락이 완료될 수 있으며, 이 경우 이미 사용된 초대가 다시 대기 상태로 변경될 수 있습니다.
 */
class WorkspaceInvitationWriterImpl implements WorkspaceInvitationWriter {

  private static final Duration TIME_TO_LIVE = Duration.ofDays(7);

  private final WorkspaceInvitationRepository repository;
  private final PendingInvitationUpserter upserter;
  private final TransactionTemplate transactionTemplate;
  private final WorkspaceReader workspaceReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final UserService userService;
  private final InvitationEmailSender emailSender;
  private final Clock clock;

  WorkspaceInvitationWriterImpl(
      WorkspaceInvitationRepository repository,
      PendingInvitationUpserter upserter,
      TransactionTemplate transactionTemplate,
      WorkspaceReader workspaceReader,
      WorkspaceMembershipReader workspaceMembershipReader,
      UserService userService,
      InvitationEmailSender emailSender,
      Clock clock) {
    this.repository = repository;
    this.upserter = upserter;
    this.transactionTemplate = transactionTemplate;
    this.workspaceReader = workspaceReader;
    this.workspaceMembershipReader = workspaceMembershipReader;
    this.userService = userService;
    this.emailSender = emailSender;
    this.clock = clock;
  }

  @Override
  public WorkspaceInvitationDetail create(CreateInvitationCommand command) {
    String email = normalizeEmail(command.email());
    if (email.isEmpty()) {
      throw new BusinessException(
          InvitationErrorCode.INVITATION_INVALID_EMAIL,
          Map.of("email", String.valueOf(command.email())));
    }
    requireNotMember(command.workspaceId(), email);

    WorkspaceDetail workspace = requireWorkspace(command.workspaceId());
    UserProfile inviter = userService.getProfile(command.inviterUserId());

    String rawToken = InvitationToken.generate();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(TIME_TO_LIVE);
    UUID invitationId =
        transactionTemplate.execute(
            status ->
                upserter.upsert(
                    command.workspaceId(),
                    email,
                    command.role().value(),
                    command.inviterUserId(),
                    InvitationToken.hash(rawToken),
                    expiresAt,
                    now));

    return sendAndMarkSent(
        invitationId,
        workspace.name(),
        inviter,
        email,
        command.role().value(),
        rawToken,
        expiresAt);
  }

  @Override
  public WorkspaceInvitationDetail resend(ResendInvitationCommand command) {
    WorkspaceInvitationDetail invitation =
        requireNotAccepted(command.workspaceId(), command.invitationId());
    WorkspaceDetail workspace = requireWorkspace(command.workspaceId());
    UUID inviterId =
        invitation.inviterId() == null ? command.requesterUserId() : invitation.inviterId();
    UserProfile inviter = userService.getProfile(inviterId);

    String rawToken = InvitationToken.generate();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(TIME_TO_LIVE);
    int rotated =
        transactionTemplate.execute(
            status ->
                repository.rotateToken(
                    invitation.id(), InvitationToken.hash(rawToken), expiresAt, now));
    if (rotated == 0) {
      throw alreadyAccepted(invitation.id());
    }

    return sendAndMarkSent(
        invitation.id(),
        workspace.name(),
        inviter,
        invitation.email(),
        invitation.role(),
        rawToken,
        expiresAt);
  }

  @Override
  public WorkspaceInvitationDetail revoke(RevokeInvitationCommand command) {
    WorkspaceInvitationDetail invitation =
        requireNotAccepted(command.workspaceId(), command.invitationId());
    return transactionTemplate.execute(
        status -> {
          Instant now = clock.instant();
          if (repository.revoke(invitation.id(), now) == 0) {
            throw alreadyAccepted(invitation.id());
          }
          return detail(invitation.id());
        });
  }

  private WorkspaceInvitationDetail sendAndMarkSent(
      UUID invitationId,
      String workspaceName,
      UserProfile inviter,
      String recipientEmail,
      String role,
      String rawToken,
      Instant expiresAt) {
    try {
      emailSender.send(
          new InvitationEmail(
              workspaceName,
              inviter.name(),
              inviter.email(),
              recipientEmail,
              role,
              rawToken,
              expiresAt));
    } catch (EmailSendFailedException e) {
      throw new BusinessException(
          InvitationErrorCode.INVITATION_EMAIL_SEND_FAILED,
          Map.of("invitation_id", invitationId.toString()));
    }
    return transactionTemplate.execute(
        status -> {
          WorkspaceInvitation invitation = requireInvitation(invitationId);
          invitation.markSent(clock.instant());
          repository.flush();
          return InvitationDetailMapper.toDetail(invitation, clock.instant());
        });
  }

  private void requireNotMember(UUID workspaceId, String normalizedEmail) {
    List<UUID> memberUserIds = workspaceMembershipReader.listMemberUserIds(workspaceId);
    boolean alreadyMember =
        userService.getProfiles(memberUserIds).stream()
            .anyMatch(profile -> normalizeEmail(profile.email()).equals(normalizedEmail));
    if (alreadyMember) {
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_MEMBER_ALREADY_EXISTS, Map.of("email", normalizedEmail));
    }
  }

  private WorkspaceInvitationDetail requireNotAccepted(UUID workspaceId, UUID invitationId) {
    WorkspaceInvitationDetail invitation =
        transactionTemplate.execute(
            status ->
                repository
                    .findByIdAndWorkspaceId(invitationId, workspaceId)
                    .map(found -> InvitationDetailMapper.toDetail(found, clock.instant()))
                    .orElseThrow(
                        () ->
                            new BusinessException(
                                InvitationErrorCode.INVITATION_NOT_FOUND,
                                Map.of("invitation_id", invitationId.toString()))));
    if (invitation.status() == works.momens.server.workspace.invitation.InvitationStatus.ACCEPTED) {
      throw alreadyAccepted(invitationId);
    }
    return invitation;
  }

  private WorkspaceDetail requireWorkspace(UUID workspaceId) {
    return workspaceReader
        .findById(workspaceId)
        .orElseThrow(
            () ->
                new BusinessException(
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                    Map.of("workspace_id", workspaceId.toString())));
  }

  private WorkspaceInvitationDetail detail(UUID invitationId) {
    return InvitationDetailMapper.toDetail(requireInvitation(invitationId), clock.instant());
  }

  private WorkspaceInvitation requireInvitation(UUID invitationId) {
    return repository
        .findById(invitationId)
        .orElseThrow(
            () ->
                new BusinessException(
                    InvitationErrorCode.INVITATION_NOT_FOUND,
                    Map.of("invitation_id", invitationId.toString())));
  }

  private static BusinessException alreadyAccepted(UUID invitationId) {
    return new BusinessException(
        InvitationErrorCode.INVITATION_ALREADY_ACCEPTED,
        Map.of("invitation_id", invitationId.toString()));
  }

  private static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }
}
