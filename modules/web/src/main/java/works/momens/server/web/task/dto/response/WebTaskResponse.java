package works.momens.server.web.task.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import works.momens.server.project.task.TaskSnapshot;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "웹 작업")
public record WebTaskResponse(
    @Schema(description = "작업 식별자", example = "30d9e9fe-f43b-4097-a88e-dc19f0a5b025") UUID id,
    @Schema(description = "소속 프로젝트 식별자") UUID projectId,
    @Schema(description = "마일스톤 식별자", nullable = true) UUID milestoneId,
    @Schema(description = "사람이 읽는 작업 레이블", example = "MOM-861", nullable = true) String label,
    @Schema(description = "제목", example = "웹 task read 이관") String title,
    @Schema(description = "설명", nullable = true) String description,
    @Schema(description = "상태", example = "todo") String status,
    @Schema(description = "우선순위", example = "medium") String priority,
    @Schema(description = "담당자 식별자", nullable = true) UUID assigneeId,
    @Schema(description = "마감일", example = "2026-08-31", nullable = true) LocalDate dueDate,
    @Schema(description = "생성 시각(UTC)", example = "2026-08-20T00:00:00Z") Instant createdAt,
    @Schema(description = "수정 시각(UTC)", example = "2026-08-20T00:00:00Z") Instant updatedAt) {
  public static WebTaskResponse from(TaskSnapshot task) {
    return new WebTaskResponse(
        task.id(),
        task.projectId(),
        task.milestoneId(),
        task.label(),
        task.title(),
        task.description(),
        task.status(),
        task.priority(),
        task.assigneeId(),
        task.dueDate(),
        task.createdAt(),
        task.updatedAt());
  }
}
