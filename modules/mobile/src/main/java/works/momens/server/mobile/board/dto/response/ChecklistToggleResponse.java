package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.board.MobileTaskDetail;
import works.momens.server.project.task.TaskDetail;

/** 수정한 항목과 태스크 전체 완료 수를 함께 반환해 화면의 완료 헤더를 갱신합니다. */
@Schema(description = "완료 기준 완료 상태 변경 응답")
public record ChecklistToggleResponse(
    @Schema(description = "완료 기준 요약") ChecklistResponse checklist,
    @Schema(description = "수정 충돌 확인용 버전", minimum = "0") long version) {

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
    return new ChecklistToggleResponse(
        new ChecklistResponse(completedCount, items.size(), item), detail.version());
  }
}
