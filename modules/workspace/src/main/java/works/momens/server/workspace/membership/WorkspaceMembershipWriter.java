package works.momens.server.workspace.membership;

/**
 * workspace 멤버십 변경 public API.
 *
 * <p>멤버십을 변경할 수 있는지에 관한 도메인 규칙은 이 모듈이 소유합니다. 요청자의 역할이 충분한지는 호출하는 쪽에서 먼저 확인합니다.
 */
import java.util.Optional;

public interface WorkspaceMembershipWriter {

  /**
   * 사용자가 아직 워크스페이스 멤버가 아닌 경우에만 멤버십을 추가하고 새로 생성한 멤버십을 반환합니다.
   *
   * <p>이미 멤버인 경우에는 빈 값을 반환합니다. 초대 수락 흐름에서는 이 반환값으로 기존 멤버 여부를 판정하며, 워크스페이스 생성 시 owner 멤버십을 추가하는
   * 경로에서도 같은 메서드를 사용합니다.
   */
  Optional<WorkspaceMembershipDetail> addIfAbsent(AddMembershipCommand command);

  /**
   * 이메일로 사용자를 조회해 초대 없이 워크스페이스 멤버로 바로 추가합니다.
   *
   * <p>사용자가 이미 같은 역할로 참여하고 있으면 아무 값도 변경하지 않고 성공으로 처리합니다. 다른 역할로 참여하고 있으면 기존 역할을 덮어쓰지 않고 요청을 거부합니다.
   * 이메일에 해당하는 사용자가 없는 경우와 기존 역할이 다른 경우를 모두 403으로 처리하는 것은 이 경로의 모든 실패를 403으로 응답하는 레거시 동작을 따른 것입니다.
   */
  void addByEmail(AddMembershipByEmailCommand command);

  /**
   * 멤버의 역할을 변경합니다.
   *
   * <p>대상이 멤버가 아니면 {@code WORKSPACE_MEMBER_NOT_FOUND}, 대상이 owner이면 {@code
   * WORKSPACE_OWNER_PROTECTED}를 던집니다. 요청자가 owner이더라도 다른 owner의 역할은 변경할 수 없습니다.
   */
  void changeRole(ChangeMembershipRoleCommand command);

  /**
   * 멤버를 워크스페이스에서 제거합니다.
   *
   * <p>요청자와 대상이 같으면 {@code WORKSPACE_SELF_REMOVAL_NOT_ALLOWED}를 던집니다. 자기 자신인지 먼저 확인한 뒤 대상 멤버를
   * 조회하므로, 존재하지 않는 사용자 ID에 요청자 자신의 ID를 전달해도 자기 제거 요청으로 판정합니다. 레거시와 동일한 확인 순서입니다.
   */
  void remove(RemoveMembershipCommand command);
}
