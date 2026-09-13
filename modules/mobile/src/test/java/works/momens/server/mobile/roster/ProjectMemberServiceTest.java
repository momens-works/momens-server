package works.momens.server.mobile.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 프로젝트 멤버 조회 조합 규칙 검증. 도메인 모듈 public API는 각자 통합 테스트에서 검증하므로 여기서는 모두 mock으로 두고 조합 규칙(권한 검사 순서, 검색,
 * 정렬)만 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

  @Mock private ProjectReader projectReader;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private UserService userService;
  @InjectMocks private ProjectMemberService projectMemberService;

  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void listThrowsProjectNotFoundWhenProjectMissing() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> projectMemberService.list(PROJECT_ID, CALLER_ID, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  void listThrowsForbiddenWhenCallerIsNotInMembershipSnapshot() {
    // 요청자의 접근 권한은 별도로 조회하지 않고, 응답에 사용할 멤버 목록을 기준으로 확인합니다.
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.listMemberUserIds(WORKSPACE_ID))
        .thenReturn(List.of(UUID.randomUUID()));

    assertThatThrownBy(() -> projectMemberService.list(PROJECT_ID, CALLER_ID, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void listSortsByNameThenIdWithoutQuery() {
    // 같은 이름이 둘일 때 id 보조 정렬로 순서가 결정적인지까지 본다.
    UUID firstGyuil = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID secondGyuil = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID jinsu = UUID.fromString("00000000-0000-0000-0000-000000000003");
    stubMembers(
        profile(jinsu, "신진수", null),
        profile(secondGyuil, "김규일", "https://a/2.png"),
        profile(firstGyuil, "김규일", "https://a/1.png"));

    List<ProjectMember> members = projectMemberService.list(PROJECT_ID, jinsu, null);

    assertThat(members)
        .containsExactly(
            new ProjectMember(firstGyuil, "김규일", "https://a/1.png"),
            new ProjectMember(secondGyuil, "김규일", "https://a/2.png"),
            new ProjectMember(jinsu, "신진수", null));
  }

  @Test
  void listFiltersByNameContainingIgnoringCase() {
    UUID jinsu = UUID.randomUUID();
    UUID gyuil = UUID.randomUUID();
    stubMembers(profile(jinsu, "신진수", null), profile(gyuil, "Gyuil Kim", null));

    // 앞뒤 공백은 trim하고, 대소문자를 무시한 부분 일치로 거른다(2026-07-04 가결정).
    assertThat(projectMemberService.list(PROJECT_ID, jinsu, " gYuIl "))
        .extracting(ProjectMember::id)
        .containsExactly(gyuil);
    assertThat(projectMemberService.list(PROJECT_ID, jinsu, "진수"))
        .extracting(ProjectMember::id)
        .containsExactly(jinsu);
  }

  @Test
  void listReturnsAllMembersForBlankQuery() {
    UUID jinsu = UUID.randomUUID();
    stubMembers(profile(jinsu, "신진수", null), profile(UUID.randomUUID(), "김규일", null));

    assertThat(projectMemberService.list(PROJECT_ID, jinsu, "   ")).hasSize(2);
  }

  @Test
  void listSkipsMembershipWhoseUserProfileIsMissing() {
    UUID existing = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.listMemberUserIds(WORKSPACE_ID))
        .thenReturn(List.of(existing, missing));
    // getProfiles는 없는 id를 에러 없이 결과에서 뺀다(user 모듈 계약). 응답은 프로필 기준이라 그대로 빠진다.
    when(userService.getProfiles(List.of(existing, missing)))
        .thenReturn(List.of(profile(existing, "신진수", null)));

    assertThat(projectMemberService.list(PROJECT_ID, existing, null))
        .extracting(ProjectMember::id)
        .containsExactly(existing);
  }

  /** profiles를 workspace 멤버로 stub한다. 접근 검사가 이 스냅샷 기준이라 호출자도 profiles 중 하나여야 한다. */
  private void stubMembers(UserProfile... profiles) {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    List<UUID> memberIds = List.of(profiles).stream().map(UserProfile::id).toList();
    when(workspaceMembershipReader.listMemberUserIds(WORKSPACE_ID)).thenReturn(memberIds);
    when(userService.getProfiles(memberIds)).thenReturn(List.of(profiles));
  }

  private static UserProfile profile(UUID id, String name, String avatarUrl) {
    return new UserProfile(
        id, name + "@momens.works", name, null, avatarUrl, Instant.now(), Instant.now());
  }
}
