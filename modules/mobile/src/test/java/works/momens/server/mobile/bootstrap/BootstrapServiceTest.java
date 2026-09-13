package works.momens.server.mobile.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.mobile.bootstrap.BootstrapContext.AccessibleProject;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.core.ProjectSnapshot;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.UserWorkspaceMembership;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * bootstrap 조합 규칙 검증. 도메인 모듈 public API는 각자 통합 테스트에서 검증하므로 여기서는 모두 mock으로 두고 조합 규칙(기본 project 선정,
 * workspace role 매핑, 빈 목록 정책)만 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapServiceTest {

  @Mock private UserService userService;
  @Mock private ProjectReader projectReader;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @InjectMocks private BootstrapService bootstrapService;

  private static final UUID USER_ID = UUID.randomUUID();

  @Test
  void loadPicksNewestProjectAsDefaultAndMapsWorkspaceRoles() {
    UUID ownedWorkspace = UUID.randomUUID();
    UUID joinedWorkspace = UUID.randomUUID();
    UUID newer = UUID.randomUUID();
    UUID older = UUID.randomUUID();
    when(userService.getProfile(USER_ID)).thenReturn(profile());
    when(workspaceMembershipReader.listUserMemberships(USER_ID))
        .thenReturn(
            List.of(
                new UserWorkspaceMembership(ownedWorkspace, "owner"),
                new UserWorkspaceMembership(joinedWorkspace, "member")));
    // 멤버십 스냅샷의 workspace id로 project를 조회한다. listByWorkspaceIds는 생성 최신순 정렬을
    // 보장하고(MOM-59), 조합 서비스는 그 순서를 그대로 신뢰한다.
    when(projectReader.listByWorkspaceIds(Set.of(ownedWorkspace, joinedWorkspace)))
        .thenReturn(
            List.of(
                snapshot(newer, joinedWorkspace, "newer"),
                snapshot(older, ownedWorkspace, "older")));

    BootstrapContext context = bootstrapService.load(USER_ID);

    assertThat(context.me().id()).isEqualTo(USER_ID);
    assertThat(context.defaultProjectId()).isEqualTo(newer);
    assertThat(context.projects())
        .containsExactly(
            new AccessibleProject(newer, "newer", "member"),
            new AccessibleProject(older, "older", "owner"));
  }

  @Test
  void loadReturnsNullDefaultAndEmptyProjectsForUserWithoutProjects() {
    when(userService.getProfile(USER_ID)).thenReturn(profile());
    when(workspaceMembershipReader.listUserMemberships(USER_ID)).thenReturn(List.of());
    when(projectReader.listByWorkspaceIds(Set.of())).thenReturn(List.of());

    BootstrapContext context = bootstrapService.load(USER_ID);

    // 프로젝트가 하나도 없으면 200 응답 재료로 null과 빈 목록을 내린다(0개 응답은 2026-07-04 가결정, 기획 확인 후 확정).
    assertThat(context.defaultProjectId()).isNull();
    assertThat(context.projects()).isEmpty();
  }

  private static UserProfile profile() {
    return new UserProfile(
        USER_ID, "minji@momens.works", "김민지", null, null, Instant.now(), Instant.now());
  }

  private static ProjectSnapshot snapshot(UUID id, UUID workspaceId, String name) {
    return new ProjectSnapshot(id, workspaceId, name, null, null);
  }
}
