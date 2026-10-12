package works.momens.server.web.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskStatus;

@Schema(description = "웹 작업 생성 요청")
public record CreateWebTaskRequest(
    @Schema(description = "작업 제목", requiredMode = Schema.RequiredMode.REQUIRED) String title,
    @Schema(description = "작업 설명") String description,
    @Schema(description = "작업 status. 생략하면 backlog를 사용합니다.") TaskStatus status,
    @Schema(description = "마일스톤 식별자", format = "uuid") UUID milestoneId,
    @Schema(description = "작업 priority. 생략하면 medium을 사용합니다.") TaskPriority priority,
    @Schema(description = "담당자 식별자", format = "uuid") UUID assigneeId,
    @Schema(description = "마감일", format = "date") LocalDate dueDate) {}
