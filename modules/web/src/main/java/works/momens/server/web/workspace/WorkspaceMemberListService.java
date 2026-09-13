package works.momens.server.web.workspace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 멤버 목록을 구성하는 조회 서비스입니다.
 *
 * <p>멤버십은 {@code workspace} 모듈이 소유하고 이름과 이메일은 {@code user} 모듈이 소유하므로, 두 모듈의 정보를 해당 서비스에서 결합합니다.
 *
 * <p>멤버 목록 조회(H027)와 snapshot 조회(H023)가 같은 멤버 목록을 반환하므로 정보 결합과 접근 권한 판정을 한곳에서 처리합니다. 각 조회 경로에서 목록을
 * 별도로 구성하면 정렬 기준과 같은 응답 계약이 서로 달라질 수 있습니다.
 *
 * <p>멤버십은 한 번만 조회하고, 조회 결과를 요청자의 멤버 여부 판정과 응답 목록 구성에 함께 사용합니다. 두 작업에서 멤버십을 각각 조회하면 READ COMMITTED
 * 격리 수준에서는 SQL 문이 시작될 때마다 최신 커밋을 확인합니다. 따라서 권한 확인과 목록 조회 사이에 멤버십이 회수되어도 해당 사용자에게 목록이 반환될 수 있습니다.
 *
 * <p>정렬은 {@code workspace} 모듈이 SQL에서 처리하므로 해당 서비스는 전달받은 순서를 유지합니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceMemberListService {

  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final UserService userService;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  /**
   * 요청자가 워크스페이스 멤버가 아니면 {@code AUTH_FORBIDDEN}을 던집니다.
   *
   * <p>워크스페이스 존재 여부는 호출하는 쪽에서 확인합니다. 멤버 목록 조회는 존재하지 않는 워크스페이스에 404를 먼저 반환해야 하고, snapshot 조회는 응답에
   * 포함할 워크스페이스 정보를 조회해야 하므로 두 호출 지점이 각자의 방식으로 이미 확인합니다.
   *
   * <p>사용자 정보를 찾을 수 없는 멤버십은 응답에서 제외합니다.
   */
  @Transactional(readOnly = true)
  public List<WorkspaceMemberView> list(UUID workspaceId, UUID requesterId) {
    List<WorkspaceMembershipDetail> memberships =
        workspaceMembershipReader.listMembershipDetails(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(
        workspaceId, requesterRoleOf(memberships, requesterId), WorkspaceRole.MEMBER);
    Map<UUID, UserProfile> profiles =
        userService
            .getProfiles(memberships.stream().map(WorkspaceMembershipDetail::userId).toList())
            .stream()
            .collect(Collectors.toMap(UserProfile::id, Function.identity()));
    return memberships.stream()
        .filter(membership -> profiles.containsKey(membership.userId()))
        .map(
            membership -> {
              UserProfile profile = profiles.get(membership.userId());
              return new WorkspaceMemberView(
                  profile.id(),
                  profile.email(),
                  profile.name(),
                  membership.role(),
                  membership.createdAt(),
                  membership.updatedAt());
            })
        .toList();
  }

  private static Optional<WorkspaceRole> requesterRoleOf(
      List<WorkspaceMembershipDetail> memberships, UUID requesterId) {
    return memberships.stream()
        .filter(membership -> membership.userId().equals(requesterId))
        .findFirst()
        .map(WorkspaceMembershipDetail::role)
        .flatMap(WorkspaceRole::from);
  }
}
