package works.momens.server.workspace.invitation;

/**
 * 워크스페이스 초대 생성, 재발송, 폐기를 담당하는 public API입니다.
 *
 * <p>초대 생성과 재발송에는 이메일 발송이 포함됩니다. 이메일 발송에 실패하면 초대 행은 유지되고 마지막 발송 시각만 비어 있는 상태로 남으며, 호출한 쪽에는 502를
 * 반환합니다. 레거시와 동일한 동작입니다.
 *
 * <p>초대 수락은 {@link WorkspaceInvitationAcceptor}로 분리합니다. 이 API의 세 기능은 워크스페이스 관리자만 호출할 수 있지만, 초대 수락은
 * 로그인한 사용자라면 누구나 호출할 수 있어 호출 화면과 권한 조건이 서로 다릅니다.
 */
public interface WorkspaceInvitationWriter {

  WorkspaceInvitationDetail create(CreateInvitationCommand command);

  WorkspaceInvitationDetail resend(ResendInvitationCommand command);

  WorkspaceInvitationDetail revoke(RevokeInvitationCommand command);
}
