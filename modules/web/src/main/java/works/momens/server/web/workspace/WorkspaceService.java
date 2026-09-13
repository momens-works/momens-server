package works.momens.server.web.workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.onboarding.WorkspaceOnboarding;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.UpdateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceEditor;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.core.WorkspaceSlugAvailability;
import works.momens.server.workspace.core.WorkspaceSlugReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 조합 서비스입니다. 도메인 public API를 조합하며 정책은 소유하지 않습니다.
 *
 * <p>{@link WorkspaceReader}는 {@link Optional}을 반환하므로 조회 결과가 없을 때 사용할 에러는 해당 서비스에서 결정합니다. project
 * 모듈의 {@code ProjectReader}와 같은 방식입니다. 목록 조회는 멤버십 조인이 조회 대상을 제한하므로 별도의 권한 검사를 수행하지 않습니다.
 *
 * <p>생성 요청은 {@code onboarding} 모듈에 위임합니다. 워크스페이스와 함께 프로젝트와 메모리를 저장하므로 트랜잭션이 세 도메인 모듈에 걸쳐 있기 때문입니다.
 * 저장 순서와 트랜잭션 경계는 {@code onboarding} 모듈이 소유하므로 해당 서비스의 생성 메서드에는 {@code @Transactional}을 선언하지 않습니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceService {

  private final WorkspaceReader workspaceReader;
  private final WorkspaceSlugReader workspaceSlugReader;
  private final WorkspaceEditor workspaceEditor;
  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final WorkspaceOnboarding workspaceOnboarding;

  public WorkspaceDetail create(UUID requesterId, String name, String description, String slug) {
    return workspaceOnboarding.createWorkspace(
        new CreateWorkspaceCommand(requesterId, name, description, slug));
  }

  @Transactional(readOnly = true)
  public List<WorkspaceDetail> list(UUID userId) {
    return workspaceReader.listByMemberUserId(userId);
  }

  @Transactional(readOnly = true)
  public WorkspaceSlugAvailability slugAvailability(String rawSlug) {
    return workspaceSlugReader.availabilityOf(rawSlug);
  }

  @Transactional(readOnly = true)
  public WorkspaceDetail get(UUID workspaceId, UUID userId) {
    WorkspaceDetail detail =
        workspaceReader
            .findById(workspaceId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        Map.of("workspace_id", workspaceId.toString())));
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return detail;
  }

  /**
   * 워크스페이스를 수정합니다.
   *
   * <p>워크스페이스의 존재 여부를 먼저 확인한 뒤 역할을 검증합니다. 순서를 바꾸면 존재하지 않는 워크스페이스에도 404가 아닌 403을 반환하게 됩니다.
   */
  @Transactional
  public WorkspaceDetail update(
      UUID workspaceId, UUID userId, String name, String description, String slug) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    return workspaceEditor.update(new UpdateWorkspaceCommand(workspaceId, name, description, slug));
  }
}
