package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import works.momens.server.mobile.MobileTaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskSnapshot;

@Schema(description = "작업 생성 응답")
public record TaskCreateResponse(@Schema(description = "생성된 작업") TaskResponse task) {

  @Schema(description = "생성된 작업")
  public record TaskResponse(
      @Schema(description = "작업 식별자") UUID id,
      @Schema(description = "프로젝트 식별자") UUID projectId,
      @Schema(description = "제목") String title,
      @Schema(description = "role 목록입니다. `pm`, `design`, `backend`, `frontend` 순서로 정렬합니다.")
          List<String> roles,
      @Schema(
              description = "`roles`로 대체된 필드입니다. `roles`의 첫 번째 값을 반환합니다.",
              example = "pm",
              deprecated = true)
          String role,
      @Schema(description = "우선순위 (urgent/high/medium/low)", example = "medium") String priority,
      @Schema(description = "상태", example = "todo") String status) {}

  public static TaskCreateResponse from(TaskSnapshot created, Set<TaskRole> roles) {
    List<String> values = roles.stream().sorted().map(TaskRole::value).toList();
    return new TaskCreateResponse(
        new TaskResponse(
            created.id(),
            created.projectId(),
            created.title(),
            values,
            values.isEmpty() ? null : values.getFirst(),
            MobileTaskPriority.key(created.priority()),
            created.status()));
  }
}
