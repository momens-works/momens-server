package works.momens.server.web.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskStatus;

@Schema(description = "웹 태스크 생성 요청")
public record CreateWebTaskRequest(
    @Schema(description = "태스크 제목", requiredMode = Schema.RequiredMode.REQUIRED) String title,
    @Schema(description = "태스크 설명") String description,
    @Schema(description = "태스크 status. 생략하면 backlog를 사용합니다.") TaskStatus status,
    @Schema(description = "마일스톤 식별자", format = "uuid") UUID milestoneId,
    @Schema(description = "태스크 priority. 생략하면 medium을 사용합니다.") TaskPriority priority,
    @Schema(description = "담당자 식별자", format = "uuid") UUID assigneeId,
    @Schema(description = "마감일", format = "date") LocalDate dueDate) {}
