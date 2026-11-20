package works.momens.server.web.blocker;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerErrorCode;
import works.momens.server.project.blocker.BlockerReader;
import works.momens.server.project.blocker.BlockerWriter;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskScope;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class BlockerWriteService {
  private final BlockerWriter blockerWriter;
  private final BlockerReader blockerReader;
  private final TaskReader taskReader;
  private final ProjectReader projectReader;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  @Transactional
  BlockerDetail create(UUID taskId, UUID userId, UUID requestedWorkspaceId, String description) {
    TaskScope task =
        taskReader
            .findScope(taskId)
            .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
    // 레거시는 살아 있는 프로젝트에서 workspace를 확인합니다.
    UUID workspaceId =
        projectReader
            .workspaceIdOf(task.projectId())
            .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
    if (requestedWorkspaceId != null && !requestedWorkspaceId.equals(workspaceId)) {
      throw new BusinessException(BlockerErrorCode.BLOCKER_WORKSPACE_MISMATCH);
    }
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return blockerWriter.createForTask(workspaceId, taskId, description);
  }

  @Transactional
  void resolve(UUID blockerId, UUID userId) {
    UUID workspaceId = requireBlockerRole(blockerId, userId, WorkspaceRole.MEMBER);
    blockerWriter.resolve(workspaceId, blockerId);
  }

  @Transactional
  void delete(UUID blockerId, UUID userId) {
    UUID workspaceId = requireBlockerRole(blockerId, userId, WorkspaceRole.ADMIN);
    blockerWriter.delete(workspaceId, blockerId);
  }

  private UUID requireBlockerRole(UUID blockerId, UUID userId, WorkspaceRole role) {
    UUID workspaceId =
        blockerReader
            .workspaceIdOf(blockerId)
            .orElseThrow(() -> new BusinessException(BlockerErrorCode.BLOCKER_NOT_FOUND));
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, role);
    return workspaceId;
  }
}
