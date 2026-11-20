package works.momens.server.web.task.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import works.momens.server.project.task.TaskSnapshot;

@Schema(description = "프로젝트 작업 목록 응답")
public record WebTaskListResponse(
    @Schema(description = "생성 시각 내림차순 작업 목록") List<WebTaskResponse> tasks) {
  public static WebTaskListResponse from(List<TaskSnapshot> tasks) {
    return new WebTaskListResponse(tasks.stream().map(WebTaskResponse::from).toList());
  }
}
