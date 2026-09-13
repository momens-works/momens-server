package works.momens.server.mobile.bootstrap;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.mobile.bootstrap.BootstrapContext.AccessibleProject;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.UserWorkspaceMembership;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 모바일 진입 컨텍스트 조합 서비스. 도메인 모듈 public API 3개(user, project, workspace)만 조합하고 도메인 정책을 소유하지 않습니다.
 *
 * <p>readOnly 트랜잭션을 여기(조합 경계)에 두면 안쪽 리더들이 REQUIRED 전파로 합류해 한 트랜잭션에서 일관되게 읽습니다. {@code
 * projects[].role}은 별도 project 멤버 테이블 없이 소속 workspace 멤버십 role로 매핑합니다(요구사항 명세 권한 요구사항).
 */
@Service
@RequiredArgsConstructor
class BootstrapService {

  private final UserService userService;
  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  @Transactional(readOnly = true)
  public BootstrapContext load(UUID userId) {
    UserProfile me = userService.getProfile(userId);
    // 멤버십은 여기서 한 번만 읽고, project 목록도 이 스냅샷의 workspace id로 조회합니다. 멤버십을
    // 두 번 읽으면 READ_COMMITTED에서는 문장마다 최신 커밋을 봐서, 두 조회 사이에 멤버십이 회수될
    // 때 role을 못 찾는 project가 생길 수 있습니다(PR #42 리뷰 반영).
    Map<UUID, String> roleByWorkspaceId =
        workspaceMembershipReader.listUserMemberships(userId).stream()
            .collect(
                Collectors.toMap(
                    UserWorkspaceMembership::workspaceId, UserWorkspaceMembership::role));
    List<AccessibleProject> projects =
        projectReader.listByWorkspaceIds(roleByWorkspaceId.keySet()).stream()
            .map(
                snapshot ->
                    new AccessibleProject(
                        snapshot.id(),
                        snapshot.name(),
                        roleByWorkspaceId.get(snapshot.workspaceId())))
            .toList();
    // 기본 project는 임의의 1개면 충분하다고 기획이 확인했고(2026-07-04), 구현은 가장 최근에 만든
    // 것을 선택합니다. listByWorkspaceIds가 생성 최신순 정렬을 보장하므로 첫 번째를 쓰고, 하나도
    // 없으면 null과 빈 목록을 그대로 내립니다(0개 응답은 2026-07-04 가결정, 기획 확인 후 확정).
    UUID defaultProjectId = projects.isEmpty() ? null : projects.getFirst().id();
    return new BootstrapContext(me, defaultProjectId, projects);
  }
}
