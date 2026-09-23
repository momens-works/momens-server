package works.momens.server.project.task;

import java.util.List;
import java.util.UUID;

/**
 * 태스크 수정 입력.
 *
 * <p>수정 화면이 저장할 때 편집 상태 전체를 보내므로, title, role, priority, status, purpose는 항상 채워진 값으로 넘어옵니다. {@code
 * assigneeId}는 담당자를 지정하면 값이, 비우면 null이 들어옵니다. 화면이 항상 전체를 보내기 때문에 null은 담당자 비우기 하나로만 해석합니다. {@code
 * checklistItems}는 완료기준 최종 목록이고, {@code purpose}는 저장 시 {@code description} 컬럼에 매핑됩니다. role,
 * priority, status 값 검증은 표면(mobile)이 하고, 이 모듈은 저장을 책임집니다.
 */
public record UpdateTaskCommand(
    UUID taskId,
    String title,
    TaskRole role,
    UUID assigneeId,
    TaskPriority priority,
    TaskStatus status,
    String purpose,
    List<ChecklistItemEdit> checklistItems) {

  /**
   * 완료기준 한 항목의 수정 입력. id가 있으면 기존 항목, 없으면 새 항목입니다. {@code completed}는 수정 화면이 저장한 완료 상태로, 기존 항목이면 이
   * 값으로 갱신하고 새 항목이면 이 값으로 만듭니다.
   */
  public record ChecklistItemEdit(UUID id, String title, boolean completed) {}
}
