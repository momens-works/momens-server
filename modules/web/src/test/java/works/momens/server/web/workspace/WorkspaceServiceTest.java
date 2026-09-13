package works.momens.server.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.onboarding.WorkspaceOnboarding;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.UpdateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceEditor;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.core.WorkspaceSlugReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 조회 조합 규칙 검증. workspace public API는 각자 통합 테스트에서 검증하므로 여기서는 모두 mock으로 두고 에러 선택 규칙(404 vs 403
 * 우선순위)만 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

  @Mock private WorkspaceReader workspaceReader;
  @Mock private WorkspaceSlugReader workspaceSlugReader;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private WorkspaceEditor workspaceEditor;
  @Mock private WorkspaceOnboarding workspaceOnboarding;
  private WorkspaceService workspaceService;

  @BeforeEach
  void setUp() {
    workspaceService =
        new WorkspaceService(
            workspaceReader,
            workspaceSlugReader,
            workspaceEditor,
            new WorkspaceAccessChecker(workspaceReader, workspaceMembershipReader),
            workspaceOnboarding);
  }

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Test
  @DisplayName("목록은 reader 결과를 그대로 반환한다(별도 권한 검사 없음)")
  void listReturnsReaderResultAsIs() {
    WorkspaceDetail detail = detail();
    when(workspaceReader.listByMemberUserId(USER_ID)).thenReturn(List.of(detail));

    assertThat(workspaceService.list(USER_ID)).containsExactly(detail);
  }

  @Test
  @DisplayName("워크스페이스가 없으면 WORKSPACE_NOT_FOUND를 던진다")
  void getThrowsWorkspaceNotFoundWhenMissing() {
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> workspaceService.get(WORKSPACE_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
  }

  @Test
  @DisplayName("워크스페이스는 있지만 멤버가 아니면 AUTH_FORBIDDEN을 던진다")
  void getThrowsForbiddenWhenCallerIsNotMember() {
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.of(detail()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> workspaceService.get(WORKSPACE_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  @DisplayName("멤버이면 워크스페이스 상세를 반환한다")
  void getReturnsDetailWhenCallerIsMember() {
    WorkspaceDetail detail = detail();
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.of(detail));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));

    assertThat(workspaceService.get(WORKSPACE_ID, USER_ID)).isEqualTo(detail);
  }

  @Test
  @DisplayName("워크스페이스가 없으면 역할을 확인하지 않고 WORKSPACE_NOT_FOUND를 던진다")
  void updateThrowsWorkspaceNotFoundBeforeCheckingRole() {
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> workspaceService.update(WORKSPACE_ID, USER_ID, "새 이름", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
    verifyNoInteractions(workspaceMembershipReader, workspaceEditor);
  }

  @Test
  @DisplayName("멤버가 아니면 AUTH_FORBIDDEN을 던지고 수정을 시도하지 않는다")
  void updateThrowsForbiddenWhenCallerIsNotMember() {
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.of(detail()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> workspaceService.update(WORKSPACE_ID, USER_ID, "새 이름", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
    verifyNoInteractions(workspaceEditor);
  }

  @Test
  @DisplayName("멤버의 역할이 admin 미만이면 AUTH_FORBIDDEN을 던지고 수정을 시도하지 않는다")
  void updateThrowsForbiddenWhenCallerIsMemberWithoutAdminRole() {
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.of(detail()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));

    assertThatThrownBy(() -> workspaceService.update(WORKSPACE_ID, USER_ID, "새 이름", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
    verifyNoInteractions(workspaceEditor);
  }

  @Test
  @DisplayName("admin 이상의 역할이면 수정 요청을 editor에 전달한다")
  void updateDelegatesToEditorForAdminAndOwner() {
    WorkspaceDetail updated = detail();
    when(workspaceReader.findById(WORKSPACE_ID)).thenReturn(Optional.of(detail()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.ADMIN));
    when(workspaceEditor.update(new UpdateWorkspaceCommand(WORKSPACE_ID, "새 이름", null, "momens-2")))
        .thenReturn(updated);

    assertThat(workspaceService.update(WORKSPACE_ID, USER_ID, "새 이름", null, "momens-2"))
        .isEqualTo(updated);
  }

  private static WorkspaceDetail detail() {
    return new WorkspaceDetail(
        WORKSPACE_ID, "Momens", "momens", "제품팀 워크스페이스", Instant.now(), Instant.now());
  }
}
