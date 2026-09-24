package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;

/** {@code POST /api/mobile/projects/{projectId}/tasks} 응답. 생성된 태스크를 {@code task} 아래에 담습니다. */
@Schema(description = "태스크 생성 응답")
public record TaskCreateResponse(@Schema(description = "생성된 태스크") TaskResponse task) {

  @Schema(description = "생성된 태스크")
  public record TaskResponse(
      @Schema(description = "태스크 식별자") UUID id,
      @Schema(description = "프로젝트 식별자") UUID projectId,
      @Schema(description = "제목") String title,
      @Schema(description = "역할", example = "pm", implementation = TaskRole.class) String role,
      @Schema(description = "우선순위", example = "medium", implementation = MobilePriority.class)
          String priority,
      @Schema(description = "상태", example = "todo", implementation = TaskStatus.class)
          String status) {}

  public static TaskCreateResponse from(TaskSnapshot created) {
    return new TaskCreateResponse(
        new TaskResponse(
            created.id(),
            created.projectId(),
            created.title(),
            created.role(),
            created.priority(),
            created.status()));
  }
}
