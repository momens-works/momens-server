package works.momens.server.workspace.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code workspace} 모듈에서 멤버십을 조회할 때 사용하는 공개 API입니다.
 *
 * <p>다른 모듈이 {@code workspace} 내부 레포지토리를 직접 참조하지 않고 멤버십을 조회할 수 있도록 합니다({@code
 * docs/rules/code-conventions.md}의 「보호 API」 절 참고). 이 API는 멤버십 조회만 담당하며, 권한이 부족할 때 반환할 오류는 호출하는 쪽에서
 * 결정합니다. project나 task가 어느 workspace에 속하는지 판단하는 것은 이 API의 책임이 아닙니다. 멤버십 레포지토리는 {@code
 * membership.internal} 패키지에 있습니다.
 *
 * <p>{@code WorkspaceAccess}와 {@code WorkspaceRoleReader}는 MOM-0885에서 이 인터페이스로 통합되었습니다. 멤버십 조회 메서드는
 * 모두 같은 테이블을 조회하고 함께 변경되므로 하나의 인터페이스에서 관리합니다.
 */
public interface WorkspaceMembershipReader {

  /** workspaceId에 속한 userId의 역할을 조회합니다. 멤버가 아니면 빈 Optional을 반환합니다. */
  Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId);

  /**
   * 워크스페이스에 속한 멤버의 사용자 식별자 목록을 조회합니다. 역할이나 가입 시각도 필요한 호출자는 {@code listMembershipDetails}를 사용합니다. 두
   * 메서드는 동일한 정렬 기준을 적용합니다.
   */
  List<UUID> listMemberUserIds(UUID workspaceId);

  /**
   * 워크스페이스의 모든 멤버십을 가입 시각 오름차순으로 조회합니다. 가입 시각이 같으면 사용자 식별자 오름차순으로 정렬합니다.
   *
   * <p>웹 snapshot 계약 문서 4.4절에서 확정한 {@code members} 정렬 기준입니다. SQL에서 정렬을 처리하므로 호출하는 쪽에서는 전달받은 순서를
   * 유지해야 합니다.
   */
  List<WorkspaceMembershipDetail> listMembershipDetails(UUID workspaceId);

  /**
   * userId가 가진 workspace 멤버십을 role과 함께 모두 조회합니다. 접근 가능한 project 목록 조회(MOM-59)와 모바일 bootstrap의 role
   * 매핑(MOM-60)에 사용합니다.
   */
  List<UserWorkspaceMembership> listUserMemberships(UUID userId);
}
