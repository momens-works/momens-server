package works.momens.server.workspace.invitation;

/**
 * 초대 토큰을 사용해 워크스페이스에 참여하는 public API입니다.
 *
 * <p>초대 토큰 조회부터 멤버십 생성과 수락 상태 반영까지 하나의 트랜잭션으로 처리합니다. 작업이 분리되면 멤버십은 생성되었지만 초대가 대기 상태로 남아 같은 초대 링크를
 * 다시 사용할 수 있습니다.
 */
public interface WorkspaceInvitationAcceptor {

  AcceptedInvitation accept(AcceptInvitationCommand command);
}
