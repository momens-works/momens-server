package works.momens.server.web.task.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;
import works.momens.server.project.task.TaskPolicy;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

@Schema(description = "웹 작업 생성 요청")
public record CreateWebTaskRequest(
    @Schema(description = "작업 제목", requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = TaskPolicy.TITLE_MAX_LENGTH)
        String title,
    @Schema(description = "작업 설명") String description,
    @Schema(description = "작업 상태. 생략하면 backlog를 사용합니다.") TaskStatus status,
    @Schema(description = "마일스톤 식별자", format = "uuid") UUID milestoneId,
    @Schema(description = "작업 우선순위. 생략하면 medium을 사용합니다.") TaskPriority priority,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "role 목록입니다. 지정할 경우 `pm`, `design`, `backend`, `frontend` 중 최소 1개 이상을 선택해야 하며, 중복은 허용하지 않습니다. 생략하면 role이 지정되지 않은 태스크를 생성합니다."),
            uniqueItems = true)
        @Size(min = 1)
        @UniqueElements
        List<@NotNull TaskRole> roles,
    @Schema(description = "담당자 식별자", format = "uuid") UUID assigneeId,
    @Schema(description = "마감일", format = "date") LocalDate dueDate) {}
