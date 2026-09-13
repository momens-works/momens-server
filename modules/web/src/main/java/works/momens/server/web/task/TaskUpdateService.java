package works.momens.server.web.task;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskScope;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.taskupdate.CreateTaskUpdateCommand;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateReader;
import works.momens.server.project.taskupdate.TaskUpdateWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

@Service
@RequiredArgsConstructor
class TaskUpdateService {
  private final TaskReader taskReader;
  private final TaskUpdateReader taskUpdateReader;
  private final TaskUpdateWriter taskUpdateWriter;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  @Transactional(readOnly = true)
  List<TaskUpdateDetail> list(UUID taskId, UUID userId) {
    TaskSnapshot task =
        taskReader
            .findSnapshot(taskId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        TaskErrorCode.TASK_NOT_FOUND, Map.of("task_id", taskId.toString())));
    workspaceAccessChecker.requireRoleAtLeast(task.workspaceId(), userId, WorkspaceRole.MEMBER);
    return taskUpdateReader.listByTaskId(task.id());
  }

  @Transactional
  TaskUpdateDetail create(
      UUID taskId, UUID userId, String body, String kind, Map<String, Object> metadata) {
    TaskScope task = requireTaskMember(taskId, userId);
    return taskUpdateWriter.create(
        new CreateTaskUpdateCommand(
            taskId, task.workspaceId(), task.projectId(), userId, body, kind, metadata));
  }

  @Transactional
  void delete(UUID taskId, UUID updateId, UUID userId) {
    requireTaskMember(taskId, userId);
    taskUpdateWriter.delete(taskId, updateId, userId);
  }

  /**
   * 태스크가 속한 워크스페이스를 조회한 뒤 요청자가 해당 워크스페이스의 멤버인지 확인합니다.
   *
   * <p>{@code list}는 {@code findSnapshot}으로 조회한 소속 프로젝트의 워크스페이스를 기준으로 삼지만, 이 메서드는 {@code
   * findScope}가 태스크 행에서 조회한 워크스페이스를 기준으로 삼습니다. 두 기준을 통일하는 작업은 MOM-0854에서 다룹니다.
   */
  private TaskScope requireTaskMember(UUID taskId, UUID userId) {
    TaskScope task =
        taskReader
            .findScope(taskId)
            .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
    workspaceAccessChecker.requireRoleAtLeast(task.workspaceId(), userId, WorkspaceRole.MEMBER);
    return task;
  }
}
