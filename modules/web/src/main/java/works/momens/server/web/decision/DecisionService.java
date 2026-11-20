package works.momens.server.web.decision;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.decision.CreateDecisionCommand;
import works.momens.server.project.decision.DecisionDetail;
import works.momens.server.project.decision.DecisionWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.web.decision.dto.request.CreateDecisionRequest;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class DecisionService {
  private final ProjectReader projectReader;
  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final DecisionWriter decisionWriter;

  @Transactional
  public DecisionDetail create(UUID projectId, UUID userId, CreateDecisionRequest request) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return decisionWriter.create(
        new CreateDecisionCommand(
            projectId,
            workspaceId,
            userId,
            request.title(),
            request.context(),
            request.alternatives(),
            request.rationale(),
            request.reversibility()));
  }
}
