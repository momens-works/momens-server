package works.momens.server.mobile.board;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.TaskDraftStatusReader;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 모바일 태스크 표면(보드 조회, 생성, 상세 조회)의 조합 서비스입니다. project(태스크 도메인), workspace(멤버십), user(프로필), minsu(draft
 * 생성 상태) public API를 조합하고 도메인 정책을 소유하지 않습니다.
 *
 * <p>보드 그룹 구성과 상세의 purpose 개명은 모바일 조합 규칙이므로 이 서비스가 소유하고, 저장 priority 해석(urgent를 high로 반환)은 {@link
 * MobilePriority}가 소유합니다.
 *
 * <p>태스크 관련자료 목록과 개수는 {@link TaskMaterialAssembler}에 위임합니다.
 *
 * <p>조회는 read-only 트랜잭션에 두고, 생성은 라벨 발급과 저장이 한 트랜잭션으로 묶이도록 쓰기 트랜잭션에 둡니다.
 */
@Service
@RequiredArgsConstructor
class ProjectTaskService {

  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final TaskReader taskReader;
  private final TaskWriter taskWriter;
  private final UserService userService;
  private final TaskMaterialAssembler taskMaterialAssembler;
  private final TaskDraftStatusReader taskDraftStatusReader;

  @Transactional(readOnly = true)
  public List<MobileTaskGroup> getBoard(UUID projectId, UUID userId) {
    UUID workspaceId = requireProjectMember(projectId, userId);
    List<BoardTask> tasks = taskReader.listTasksByStatus(projectId, BoardStatus.keys());
    Map<UUID, Integer> materialCounts =
        taskMaterialAssembler.countMaterials(
            workspaceId, tasks.stream().map(BoardTask::id).toList());
    Map<String, List<MobileTaskCard>> cardsByStatus =
        tasks.stream()
            .collect(
                Collectors.groupingBy(
                    BoardTask::status,
                    Collectors.mapping(
                        task -> toCard(task, materialCounts.getOrDefault(task.id(), 0)),
                        Collectors.toList())));
    return Arrays.stream(BoardStatus.values())
        .map(
            status ->
                new MobileTaskGroup(status, cardsByStatus.getOrDefault(status.key(), List.of())))
        .toList();
  }

  @Transactional
  public TaskSnapshot createTask(
      UUID projectId, UUID userId, String title, String role, String priority) {
    UUID workspaceId = requireProjectMember(projectId, userId);
    return taskWriter.create(
        CreateTaskCommand.manual(projectId, workspaceId, title, role, priority));
  }

  /**
   * 태스크 상세를 조합한다. <b>원장 상태를 task보다 먼저 읽는다</b>(설계 7.3절). 순서를 뒤집으면 fallback title을 읽은 뒤 생성이 끝나는 창에서
   * "이전 title + {@code ready}"가 나가고, 앱은 재조회를 멈춰 갱신을 영구히 놓친다. 반대 순서에서 나올 수 있는 "새 title + {@code
   * generating}"은 앱이 한 번 더 조회할 뿐이라 안전하다.
   *
   * <p>이 성질은 {@code READ COMMITTED}에서 두 SELECT가 각자 snapshot을 뜨는 데 기댄다. 격리 수준을 올리거나 두 값을 한 번에 뜨도록
   * 최적화하면 순서만으로는 정합성이 유지되지 않는다.
   *
   * <p>권한은 상세가 아니라 {@code workspaceIdOf}로 먼저 확인한다. 원장 조회가 deadline으로 닫는 경로에서 counter를 올리므로(비동기 생성
   * 설계 9.3절), 권한 없는 호출자가 경보 대상 지표를 건드리게 두지 않기 위해서다. 그 조회는 title을 읽지 않아 위 순서 계약과 무관하다.
   */
  @Transactional(readOnly = true)
  public MobileTaskDetailView getTaskDetail(UUID taskId, UUID userId) {
    requireTaskMember(taskId, userId);
    DraftStatus draftStatus = taskDraftStatusReader.statusOf(taskId);
    TaskDetail detail =
        taskReader
            .findDetail(taskId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        TaskErrorCode.TASK_NOT_FOUND, Map.of("task_id", taskId.toString())));
    return new MobileTaskDetailView(draftStatus, toMobileDetail(detail));
  }

  @Transactional
  public void updateTask(
      UUID taskId,
      UUID userId,
      String title,
      String role,
      UUID assigneeId,
      String priority,
      String status,
      String purpose,
      List<ChecklistEdit> checklistItems) {
    requireTaskMember(taskId, userId);
    List<UpdateTaskCommand.ChecklistItemEdit> items =
        checklistItems.stream()
            .map(
                edit ->
                    new UpdateTaskCommand.ChecklistItemEdit(
                        edit.id(), edit.title(), edit.completed()))
            .toList();
    // 저장만 하고 상세는 반환하지 않는다. 저장 후 최신 상태는 클라이언트가 상세 조회로 다시 읽는다.
    taskWriter.update(
        new UpdateTaskCommand(taskId, title, role, assigneeId, priority, status, purpose, items));
  }

  @Transactional
  public MobileTaskDetail toggleChecklistItem(
      UUID taskId, UUID userId, UUID itemId, boolean completed) {
    requireTaskMember(taskId, userId);
    TaskDetail updated = taskWriter.toggleChecklistItem(taskId, itemId, completed);
    return toMobileDetail(updated);
  }

  private void requireTaskMember(UUID taskId, UUID userId) {
    // 수정 전에는 상세 전체를 읽을 필요가 없어 소속 projection만 조회해 멤버십을 확인한다.
    UUID workspaceId =
        taskReader
            .findScope(taskId)
            .map(scope -> scope.workspaceId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        TaskErrorCode.TASK_NOT_FOUND, Map.of("task_id", taskId.toString())));
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("task_id", taskId.toString()));
    }
  }

  private MobileTaskDetail toMobileDetail(TaskDetail detail) {
    return new MobileTaskDetail(
        detail.id(),
        detail.projectId(),
        detail.title(),
        detail.status(),
        detail.role(),
        toAssignee(detail.assigneeId()),
        MobilePriority.fromStored(detail.priority()).key(),
        detail.description(),
        detail.checklistItems(),
        taskMaterialAssembler.getMaterials(detail.workspaceId(), detail.id()),
        detail.openQuestions(),
        detail.nextAction());
  }

  private MobileTaskDetail.Assignee toAssignee(UUID assigneeId) {
    if (assigneeId == null) {
      return null;
    }
    // assignee_id는 users FK(ON DELETE SET NULL)라 값이 있으면 항상 해석된다. 불변식이 깨지면 조용히
    // 담당자 없음으로 만들지 않고 getProfile의 USER_NOT_FOUND로 크게 실패한다.
    UserProfile profile = userService.getProfile(assigneeId);
    return new MobileTaskDetail.Assignee(profile.id(), profile.name(), profile.avatarUrl());
  }

  private UUID requireProjectMember(UUID projectId, UUID userId) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("project_id", projectId.toString()));
    }
    return workspaceId;
  }

  private static MobileTaskCard toCard(BoardTask task, int materialCount) {
    return new MobileTaskCard(
        task.id(),
        task.title(),
        task.role(),
        MobilePriority.fromStored(task.priority()).key(),
        materialCount);
  }
}
