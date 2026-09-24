package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.mobile.board.MobileTaskGroup;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

/**
 * 보드 응답.
 *
 * <p>보드는 todo, in_progress, done, backlog, cancelled 다섯 그룹을 이 순서대로 반환합니다. 수정 화면이 상태 5종을 모두 편집하므로,
 * backlog나 cancelled로 변경한 태스크도 보드에서 사라지지 않도록 다섯 그룹을 모두 포함합니다(MOM-75). 태스크가 없는 그룹도 항상 반환하며, 그때
 * {@code tasks}는 빈 배열입니다.
 *
 * <p>{@code priority}는 low, medium, high로 반환합니다. 저장된 값이 레거시 전용인 {@code urgent}이면 {@code high}로
 * 매핑합니다(2026-07-06 가결정).
 *
 * <p>{@code materialCount}는 태스크에 연결된 관련자료 개수이며, 연결이 없으면 0입니다.
 *
 * <p>웹에서 생성한 태스크는 역할 정보가 없으므로 {@code role}은 {@code null}일 수 있습니다. 공유 {@code tasks} 테이블은 {@code
 * role}을 nullable로 두고, 모바일 생성 API만 {@code role}을 필수로 받습니다.
 */
@Schema(description = "프로젝트 태스크 보드 응답")
public record TaskBoardResponse(
    @Schema(description = "화면 제목", example = "프로젝트 태스크") String title,
    @Schema(description = "화면 안내 문구") String description,
    @Schema(description = "상태 그룹 목록(보드에 노출되는 순서)") List<GroupResponse> groups) {

  private static final String BOARD_TITLE = "프로젝트 태스크";
  private static final String BOARD_DESCRIPTION = "업무를 한눈에 확인하고 상세 내용을 확인하세요.";

  @Schema(description = "상태 그룹")
  public record GroupResponse(
      @Schema(description = "그룹 키", example = "todo", implementation = TaskStatus.class)
          String groupKey,
      @Schema(description = "그룹 라벨", example = "투두") String label,
      @Schema(description = "그룹 태스크 수") int count,
      @Schema(description = "그룹 태스크 목록") List<TaskCardResponse> tasks) {}

  @Schema(description = "보드 태스크 카드")
  public record TaskCardResponse(
      @Schema(description = "태스크 식별자") UUID id,
      @Schema(description = "제목") String title,
      @Schema(
              description = "역할. 웹에서 만든 태스크는 역할을 지정하지 않으면 null",
              example = "pm",
              nullable = true,
              implementation = TaskRole.class)
          String role,
      @Schema(description = "우선순위", example = "low", implementation = MobilePriority.class)
          String priority,
      @Schema(description = "관련 자료 수") int materialCount) {}

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
                        card.priority(),
                        card.materialCount()))
            .toList();
    return new GroupResponse(group.status().key(), group.status().label(), cards.size(), cards);
  }
}
