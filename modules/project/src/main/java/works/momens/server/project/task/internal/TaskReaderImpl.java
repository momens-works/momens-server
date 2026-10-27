package works.momens.server.project.task.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskScope;
import works.momens.server.project.task.TaskSnapshot;

@Service
@RequiredArgsConstructor
class TaskReaderImpl implements TaskReader {

  private final TaskRepository taskRepository;
  private final ProjectReader projectReader;

  @Override
  @Transactional(readOnly = true)
  public List<BoardTask> listTasksByStatus(UUID projectId, Collection<String> statuses) {
    return taskRepository
        .findByProjectIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(projectId, statuses)
        .stream()
        .map(TaskReaderImpl::toBoardTask)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TaskDetail> findDetail(UUID taskId) {
    // checklistItems와 openQuestions는 LAZY 컬렉션이라 이 트랜잭션 안에서 보조 SELECT로 초기화된다.
    return taskRepository.findByIdAndDeletedAtIsNull(taskId).map(TaskDetailMapper::toDetail);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TaskScope> findScope(UUID taskId) {
    return taskRepository.findScopeById(taskId);
  }

  /**
   * 웹 상세 조회에 사용합니다. soft delete된 태스크와 소속 프로젝트가 soft delete된 태스크를 모두 제외합니다.
   *
   * <p>프로젝트의 soft delete 여부는 {@code ProjectReader}를 통해 확인합니다. 응답의 workspace는 다른 조회 경로와 동일하게 태스크 행의
   * 값을 사용합니다.
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<TaskSnapshot> findSnapshot(UUID taskId) {
    return taskRepository
        .findByIdAndDeletedAtIsNull(taskId)
        .filter(task -> projectReader.workspaceIdOf(task.getProjectId()).isPresent())
        .map(TaskSnapshotMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TaskSnapshot> findSnapshotInWorkspace(UUID workspaceId, UUID taskId) {
    return taskRepository
        .findByWorkspaceIdAndIdAndDeletedAtIsNull(workspaceId, taskId)
        .map(TaskSnapshotMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TaskSnapshot> findSnapshotByLabel(UUID workspaceId, String label) {
    return taskRepository
        .findByWorkspaceIdAndLabelAndDeletedAtIsNull(workspaceId, label)
        .map(TaskSnapshotMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskSnapshot> listSnapshotsByProjectId(UUID projectId) {
    return taskRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId).stream()
        .map(TaskSnapshotMapper::toSnapshot)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskSnapshot> listSnapshotsByWorkspaceId(UUID workspaceId) {
    return taskRepository
        .findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId)
        .stream()
        .map(TaskSnapshotMapper::toSnapshot)
        .toList();
  }

  private static BoardTask toBoardTask(Task task) {
    return new BoardTask(
        task.getId(),
        task.getTitle(),
        task.getStatus(),
        task.getPriority(),
        task.getRole(),
        task.getAssigneeId(),
        task.getDueDate(),
        task.getCreatedAt());
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskSnapshot> listPage(
      UUID workspaceId,
      UUID projectId,
      UUID assigneeId,
      boolean unassignedOnly,
      String status,
      Instant before,
      UUID beforeId,
      int limit) {
    return taskRepository
        .findPage(
            workspaceId,
            projectId,
            assigneeId,
            unassignedOnly,
            status,
            before,
            beforeId,
            PageRequest.of(0, limit))
        .stream()
        .map(TaskSnapshotMapper::toSnapshot)
        .toList();
  }
}
