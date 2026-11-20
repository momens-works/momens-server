package works.momens.server.web.task.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.project.taskupdate.TaskUpdateDetail;

@Schema(description = "작업 업데이트 목록 응답")
public record TaskUpdateListResponse(
    @Schema(description = "생성 시각 오름차순 업데이트 목록") List<UpdateResponse> updates) {
  public static TaskUpdateListResponse from(List<TaskUpdateDetail> updates) {
    return new TaskUpdateListResponse(updates.stream().map(UpdateResponse::from).toList());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(description = "작업 업데이트")
  public record UpdateResponse(
      @Schema(description = "업데이트 식별자") UUID id,
      @Schema(description = "워크스페이스 식별자") UUID workspaceId,
      @Schema(description = "프로젝트 식별자") UUID projectId,
      @Schema(description = "작업 식별자") UUID taskId,
      @Schema(description = "작성자 식별자", nullable = true) UUID authorId,
      @Schema(description = "본문", example = "첫 업데이트") String body,
      @Schema(description = "유형", example = "comment") String kind,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
      Instant createdAt,
      Instant updatedAt) {
    public static UpdateResponse from(TaskUpdateDetail update) {
      return new UpdateResponse(
          update.id(),
          update.workspaceId(),
          update.projectId(),
          update.taskId(),
          update.authorId(),
          update.body(),
          update.kind(),
          update.metadata(),
          update.createdAt(),
          update.updatedAt());
    }
  }
}
