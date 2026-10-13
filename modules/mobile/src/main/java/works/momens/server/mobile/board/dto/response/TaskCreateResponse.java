package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.mobile.MobileTaskPriority;
import works.momens.server.project.task.TaskSnapshot;

@Schema(description = "작업 생성 응답")
public record TaskCreateResponse(@Schema(description = "생성된 작업") TaskResponse task) {

  @Schema(description = "생성된 작업")
  public record TaskResponse(
      @Schema(description = "작업 식별자") UUID id,
      @Schema(description = "프로젝트 식별자") UUID projectId,
      @Schema(description = "제목") String title,
      @Schema(description = "업무 역할. pm/design/backend/frontend 중 하나", example = "pm") String role,
      @Schema(description = "우선순위. low/medium/high", example = "medium") String priority,
      @Schema(description = "상태", example = "todo") String status) {}

  public static TaskCreateResponse from(TaskSnapshot created) {
    return new TaskCreateResponse(
        new TaskResponse(
            created.id(),
            created.projectId(),
            created.title(),
            created.role(),
            MobileTaskPriority.fromStored(created.priority()).key(),
            created.status()));
  }
}
