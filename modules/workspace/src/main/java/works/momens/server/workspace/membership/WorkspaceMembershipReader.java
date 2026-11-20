package works.momens.server.workspace.membership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 멤버십 조회만 담당하며 권한 부족 오류와 리소스의 소속 워크스페이스 판정은 호출자가 결정합니다. */
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
   * <p>MOM-0856에서 확정한 {@code members} 정렬 기준입니다. SQL에서 정렬을 처리하므로 호출하는 쪽에서는 전달받은 순서를 유지해야 합니다.
   */
  List<WorkspaceMembershipDetail> listMembershipDetails(UUID workspaceId);

  /**
   * userId가 가진 workspace 멤버십을 role과 함께 모두 조회합니다. 접근 가능한 project 목록 조회(MOM-59)와 모바일 bootstrap의 role
   * 매핑(MOM-60)에 사용합니다.
   */
  List<UserWorkspaceMembership> listUserMemberships(UUID userId);

  /** 가입 시각·사용자 id 오름차순으로 cursor 이후 최대 limit개를 조회합니다. */
  List<WorkspaceMembershipDetail> listPage(
      UUID workspaceId, Instant after, UUID afterId, int limit);
}
