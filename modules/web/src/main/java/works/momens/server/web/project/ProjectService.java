package works.momens.server.web.project;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.core.CreateProjectCommand;
import works.momens.server.project.core.ProjectCreator;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.web.project.dto.request.CreateProjectRequest;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 프로젝트 생성을 처리하는 조합 서비스입니다. workspace와 project의 public API를 조합하며, 도메인 정책은 직접 소유하지 않습니다.
 *
 * <p>이 서비스에서는 요청자의 권한만 확인합니다. 프로젝트를 생성하려면 admin 이상의 권한이 필요하며, 이는 레거시와 동일한 기준입니다. 워크스페이스의 존재 여부는
 * 요청자의 역할보다 먼저 확인합니다. 확인 순서를 바꾸면 존재하지 않는 워크스페이스에 대해 404가 아닌 403을 반환하게 됩니다.
 *
 * <p>저장할 값의 유효성은 project 모듈에서 검증합니다.
 */
@Service
@RequiredArgsConstructor
class ProjectService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final ProjectCreator projectCreator;

  @Transactional
  public ProjectDetail create(UUID workspaceId, UUID userId, CreateProjectRequest request) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    return projectCreator.create(
        new CreateProjectCommand(
            workspaceId,
            userId,
            request.name(),
            request.description(),
            request.targetDate(),
            request.healthStatus(),
            request.progress(),
            request.summary(),
            request.unresolvedCount(),
            request.vocSignalCount(),
            request.lastContextAt(),
            request.ownerUserIds(),
            null));
  }
}
