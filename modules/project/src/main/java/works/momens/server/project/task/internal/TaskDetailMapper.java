package works.momens.server.project.task.internal;

import java.util.List;
import works.momens.server.project.task.TaskDetail;

/**
 * Task 엔티티를 상세 조회 결과인 {@link TaskDetail}로 변환합니다.
 *
 * <p>조회(TaskReaderImpl)와 수정(TaskWriterImpl)이 같은 변환을 쓰므로 한곳에 둡니다. 저장된 값을 그대로 담고, 모바일 표기 매핑은 조회하는 쪽이
 * 정합니다. 완료기준과 열린질문은 저장 순서 그대로 매핑합니다.
 */
final class TaskDetailMapper {

  private TaskDetailMapper() {}

  static TaskDetail toDetail(Task task) {
    List<TaskDetail.ChecklistItem> checklistItems =
        task.getChecklistItems().stream()
            .map(
                item ->
                    new TaskDetail.ChecklistItem(item.getId(), item.getTitle(), item.isCompleted()))
            .toList();
    List<TaskDetail.OpenQuestion> openQuestions =
        task.getOpenQuestions().stream()
            .map(question -> new TaskDetail.OpenQuestion(question.getId(), question.getBody()))
            .toList();
    return new TaskDetail(
        task.getId(),
        task.getProjectId(),
        task.getWorkspaceId(),
        task.getTitle(),
        task.getStatus(),
        task.getPriority(),
        task.getRole(),
        task.getAssigneeId(),
        task.getDescription(),
        checklistItems,
        openQuestions,
        task.getNextAction(),
        task.getVersion());
  }
}
