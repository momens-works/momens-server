package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.board.MobileTaskCard;
import works.momens.server.mobile.board.MobileTaskGroup;

/**
 * 태스크가 없는 상태 그룹도 빈 배열로 포함합니다. urgent는 high로 표시하며 웹 태스크의 role은 null일 수 있습니다.
 *
 * <p>담당자와 마감일이 지정되지 않은 경우 해당 필드는 {@code null}로 반환합니다.
 */
@Schema(description = "프로젝트 작업 보드 응답")
public record TaskBoardResponse(
    @Schema(description = "화면 제목", example = "프로젝트 작업") String title,
    @Schema(description = "화면 안내 문구") String description,
    @Schema(description = "상태별 작업 그룹 목록(`in_progress`, `todo`, `done`, `cancelled`, `backlog` 순서)")
        List<GroupResponse> groups) {

  private static final String BOARD_TITLE = "프로젝트 작업";
  private static final String BOARD_DESCRIPTION = "작업을 한눈에 확인하고 상세 내용을 확인하세요.";

  @Schema(description = "상태 그룹")
  public record GroupResponse(
      @Schema(description = "그룹 키", example = "todo") String groupKey,
      @Schema(description = "그룹 라벨", example = "할 일") String label,
      @Schema(description = "그룹 작업 수") int count,
      @Schema(description = "그룹 작업 목록") List<TaskCardResponse> tasks) {}

  @Schema(description = "보드 작업 카드")
  public record TaskCardResponse(
      @Schema(description = "작업 식별자") UUID id,
      @Schema(description = "제목") String title,
      @Schema(
              description = "업무 역할. pm/design/backend/frontend 중 하나. 웹에서 만든 작업은 미지정이면 null",
              example = "pm",
              nullable = true)
          String role,
      @Schema(description = "담당자 정보. 미지정 시 null") AssigneeResponse assignee,
      @Schema(description = "우선순위. low/medium/high", example = "low") String priority,
      @Schema(description = "마감일. 미설정 시 null", example = "2026-09-25", nullable = true)
          LocalDate dueDate,
      @Schema(description = "연결된 소스 수") int materialCount) {}

  @Schema(name = "TaskCardAssignee", description = "카드에 표시할 담당자 정보")
  public record AssigneeResponse(
      @Schema(description = "사용자 식별자") UUID id, @Schema(description = "담당자 이름") String name) {}

  public static TaskBoardResponse from(List<MobileTaskGroup> groups) {
    return new TaskBoardResponse(
        BOARD_TITLE, BOARD_DESCRIPTION, groups.stream().map(TaskBoardResponse::toGroup).toList());
  }

  private static GroupResponse toGroup(MobileTaskGroup group) {
    List<TaskCardResponse> cards =
        group.tasks().stream()
            .map(
                card ->
                    new TaskCardResponse(
                        card.id(),
                        card.title(),
                        card.role(),
                        toAssignee(card.assignee()),
                        card.priority(),
                        card.dueDate(),
                        card.materialCount()))
            .toList();
    return new GroupResponse(group.status().key(), group.status().label(), cards.size(), cards);
  }

  private static AssigneeResponse toAssignee(MobileTaskCard.Assignee assignee) {
    if (assignee == null) {
      return null;
    }
    return new AssigneeResponse(assignee.id(), assignee.name());
  }
}
