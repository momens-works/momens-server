package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.board.MobileTaskDetail;
import works.momens.server.project.task.TaskDetail;

/**
 * {@code PATCH /api/mobile/tasks/{taskId}/checklist-items/{itemId}} 응답. 응답 형식은
 * docs/spec/mobile-api.md 태스크 수정 절을 따릅니다.
 *
 * <p>완료 상태를 바꾼 항목 하나와 함께, 태스크 전체 기준 완료 수와 전체 수를 반환합니다. 상세 화면의 완료 헤더(완료 수 / 전체 수)를 바로 갱신하기 위해서입니다.
 */
@Schema(description = "완료 기준 완료 상태 변경 응답")
public record ChecklistToggleResponse(
    @Schema(description = "완료 기준 요약") ChecklistResponse checklist) {

  @Schema(description = "완료 기준 요약")
  public record ChecklistResponse(
      @Schema(description = "완료된 항목 수") int completedCount,
      @Schema(description = "전체 항목 수") int totalCount,
      @Schema(description = "완료 상태를 바꾼 항목") ItemResponse item) {}

  @Schema(description = "완료 기준 항목")
  public record ItemResponse(
      @Schema(description = "항목 식별자") UUID id,
      @Schema(description = "항목 제목") String title,
      @Schema(description = "완료 여부") boolean completed) {}

  public static ChecklistToggleResponse from(MobileTaskDetail detail, UUID itemId) {
    List<TaskDetail.ChecklistItem> items = detail.checklistItems();
    int completedCount = (int) items.stream().filter(TaskDetail.ChecklistItem::completed).count();
    TaskDetail.ChecklistItem changed =
        items.stream()
            .filter(item -> item.id().equals(itemId))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("완료 상태를 바꾼 완료기준 항목을 응답에서 찾지 못했습니다: " + itemId));
    ItemResponse item = new ItemResponse(changed.id(), changed.title(), changed.completed());
    return new ChecklistToggleResponse(new ChecklistResponse(completedCount, items.size(), item));
  }
}
