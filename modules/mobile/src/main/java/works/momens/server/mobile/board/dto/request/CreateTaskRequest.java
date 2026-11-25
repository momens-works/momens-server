package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;
import works.momens.server.project.task.TaskPolicy;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

@Schema(description = "일반 작업 생성 요청")
public record CreateTaskRequest(
    @Schema(description = "태스크 제목. 공백만으로 구성할 수 없습니다.", example = "권한 요청 플로우 점검")
        @NotBlank
        @Size(max = TaskPolicy.TITLE_MAX_LENGTH)
        String title,
    @ArraySchema(
            arraySchema = @Schema(description = "role 목록입니다. `role`을 보내지 않으면 필수입니다."),
            uniqueItems = true)
        @Size(min = 1)
        @UniqueElements
        List<@NotNull TaskRole> roles,
    @Schema(
            deprecated = true,
            description = "`roles`로 대체된 필드입니다. `roles` 없이 `role`만 보내면 해당 role 하나로 태스크를 생성합니다.",
            example = "backend")
        TaskRole role,
    @Schema(description = "우선순위", example = "medium") @NotNull TaskPriority priority,
    @Schema(description = "마감일", example = "2026-09-25", nullable = true) LocalDate dueDate) {}
