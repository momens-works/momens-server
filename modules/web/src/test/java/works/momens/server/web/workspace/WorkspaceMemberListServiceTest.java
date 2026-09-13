package works.momens.server.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 멤버 목록 조회 서비스의 동작을 검증합니다.
 *
 * <p>{@code workspace}와 {@code user} 모듈의 public API는 각 모듈의 통합 테스트에서 검증하므로 해당 테스트에서는 {@code @Mock}으로
 * 대체합니다. 해당 서비스가 담당하는 정보 결합, 조회 순서 유지, 접근 권한 판정만 검증합니다. 정렬 기준은 SQL에서 적용하므로 {@code workspace} 모듈의 통합
 * 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceMemberListServiceTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID OTHER_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private UserService userService;
  @Mock private WorkspaceReader workspaceReader;
  private WorkspaceMemberListService workspaceMemberListService;

  @BeforeEach
  void setUp() {
    workspaceMemberListService =
        new WorkspaceMemberListService(
            workspaceMembershipReader,
            userService,
            new WorkspaceAccessChecker(workspaceReader, workspaceMembershipReader));
  }

  @Test
  @DisplayName("사용자 정보와 멤버십 정보를 결합해 반환한다")
  void listMapsProfileAndMembershipFields() {
    when(workspaceMembershipReader.listMembershipDetails(WORKSPACE_ID))
        .thenReturn(List.of(membership(CALLER_ID, "admin")));
    when(userService.getProfiles(any()))
        .thenReturn(List.of(profile(CALLER_ID, "jinsu@momens.works", "신진수")));

    WorkspaceMemberView member =
        workspaceMemberListService.list(WORKSPACE_ID, CALLER_ID).getFirst();

    assertThat(member.userId()).isEqualTo(CALLER_ID);
    assertThat(member.email()).isEqualTo("jinsu@momens.works");
    assertThat(member.name()).isEqualTo("신진수");
    assertThat(member.role()).isEqualTo("admin");
    assertThat(member.createdAt()).isEqualTo(NOW);
    assertThat(member.updatedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("이름 순서와 관계없이 조회 결과의 순서를 유지한다")
  void listKeepsReaderOrder() {
    when(workspaceMembershipReader.listMembershipDetails(WORKSPACE_ID))
        .thenReturn(List.of(membership(OTHER_ID, "owner"), membership(CALLER_ID, "member")));
    when(userService.getProfiles(any()))
        .thenReturn(
            List.of(
                profile(CALLER_ID, "later@momens.works", "가장 앞에 오는 이름"),
                profile(OTHER_ID, "earlier@momens.works", "하위에 오는 이름")));

    assertThat(workspaceMemberListService.list(WORKSPACE_ID, CALLER_ID))
        .extracting(WorkspaceMemberView::userId)
        .containsExactly(OTHER_ID, CALLER_ID);
  }

  @Test
  @DisplayName("요청자가 멤버가 아니면 사용자 정보를 조회하지 않고 거부한다")
  void listRejectsCallerWhoIsNotMember() {
    when(workspaceMembershipReader.listMembershipDetails(WORKSPACE_ID))
        .thenReturn(List.of(membership(OTHER_ID, "owner")));

    assertThatThrownBy(() -> workspaceMemberListService.list(WORKSPACE_ID, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
    verifyNoInteractions(userService);
  }

  private static WorkspaceMembershipDetail membership(UUID userId, String role) {
    return new WorkspaceMembershipDetail(userId, role, NOW, NOW);
  }

  private static UserProfile profile(UUID id, String email, String name) {
    return new UserProfile(id, email, name, null, null, NOW, NOW);
  }
}
