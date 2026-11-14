package works.momens.server.project.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 태스크 수정 입력.
 *
 * <p>수정 화면에서 저장할 때 편집 중인 모든 값을 전달하므로 title, roles, priority, status, purpose는 항상 값이 채워진 상태로 전달됩니다.
 * {@code assigneeId}와 {@code dueDate}에는 값을 지정한 경우 해당 값이, 지정하지 않은 경우 {@code null}이 전달됩니다. 수정 화면에서는
 * 편집 상태 전체를 전달하므로 {@code null}은 기존 담당자 또는 마감일을 삭제하는 의미로만 해석합니다. {@code checklistItems}는 완료기준 최종
 * 목록이고, {@code purpose}는 저장 시 {@code description} 컬럼에 매핑됩니다. roles, priority, status는 각 호출 모듈의 요청
 * DTO에서 enum 타입으로 검증하며, 이 모듈에서는 검증된 값을 저장합니다.
 *
 * <p>이 record에서 제목 길이를 직접 검증하며, 최대 길이를 초과하면 저장 전에 거부합니다.
 */
public record UpdateTaskCommand(
    UUID taskId,
    String title,
    Set<TaskRole> roles,
    UUID assigneeId,
    TaskPriority priority,
    LocalDate dueDate,
    TaskStatus status,
    String purpose,
    List<ChecklistItemEdit> checklistItems,
    Long version) {

  public UpdateTaskCommand {
    TaskPolicy.requireTitleWithinLimit(title);
  }

  /**
   * 완료기준 한 항목의 수정 입력. id가 있으면 기존 항목, 없으면 새 항목입니다. {@code completed}는 수정 화면이 저장한 완료 상태로, 기존 항목이면 이
   * 값으로 갱신하고 새 항목이면 이 값으로 만듭니다.
   */
  public record ChecklistItemEdit(UUID id, String title, boolean completed) {}
}
