package works.momens.server.mobile.board.dto.request;

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
    @Schema(description = "태스크 제목. 공백 포함 최대 70자.", example = "권한 요청 플로우 점검")
        @NotBlank
        @Size(max = TaskPolicy.TITLE_MAX_LENGTH)
        String title,
    @Schema(
            description =
                "role 목록입니다. `pm`, `design`, `backend`, `frontend` 중 최소 1개 이상 지정해야 하며, 중복은 허용하지 않습니다.")
        @Size(min = 1)
        @UniqueElements
        List<@NotNull TaskRole> roles,
    @Schema(
            deprecated = true,
            description =
                "구버전 앱과의 호환성을 위한 role 필드입니다. 새 클라이언트는 `roles`를 사용합니다. `roles` 없이 `role`만 전달하면 해당 role 하나로 태스크를 생성합니다.",
            example = "backend")
        TaskRole role,
    @Schema(description = "우선순위 (urgent/high/medium/low 중 하나)", example = "medium") @NotNull
        TaskPriority priority,
    @Schema(
            description = "마감일(YYYY-MM-DD). 생략하거나 null로 전달하면 마감일 없이 생성합니다.",
            example = "2026-09-25",
            nullable = true)
        LocalDate dueDate) {}
