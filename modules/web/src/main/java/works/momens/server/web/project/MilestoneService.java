package works.momens.server.web.project;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneCreator;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.web.project.dto.request.CreateMilestoneRequest;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 마일스톤 생성을 처리하는 조합 서비스입니다. project와 workspace의 public API를 조합하며, 도메인 정책은 직접 소유하지 않습니다.
 *
 * <p>마일스톤만으로는 소속 워크스페이스를 알 수 없으므로 프로젝트를 먼저 조회합니다. 프로젝트가 존재하지 않으면 404를 반환하고, 프로젝트가 존재하면 member 이상의
 * 권한이 있는지 확인합니다. 이는 레거시와 동일한 기준입니다. 확인이 끝난 워크스페이스 식별자는 project 모듈에 그대로 전달하여 동일한 조회를 반복하지 않습니다.
 */
@Service
@RequiredArgsConstructor
class MilestoneService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final ProjectReader projectReader;
  private final MilestoneCreator milestoneCreator;

  @Transactional
  public MilestoneDetail create(UUID projectId, UUID userId, CreateMilestoneRequest request) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return milestoneCreator.create(
        new CreateMilestoneCommand(
            projectId,
            workspaceId,
            userId,
            request.name(),
            request.description(),
            request.targetDate(),
            request.healthStatus(),
            request.progress(),
            request.summary(),
            request.lastContextAt(),
            request.ownerUserIds()));
  }
}
