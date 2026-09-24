package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.project.task.TaskRole;

/**
 * 일반 태스크 생성 요청. 요청 형식은 docs/spec/mobile-api.md 태스크 절을 따릅니다.
 *
 * <p>title, role, priority 모두 필수입니다(2026-07-06 기획 확정). role은 하나만 선택하고 pm/design/backend/frontend 중
 * 하나입니다(2026-07-08 기획 확정으로 android, qa는 폐기하고 역할은 4종만 둡니다). priority는 low/medium/high 중 하나입니다. 제목은
 * 공백을 포함해 15자로 제한하며, 수정 화면과 같은 태스크 공통 규칙입니다(task_001 화면설계서).
 */
@Schema(description = "일반 태스크 생성 요청")
public record CreateTaskRequest(
    @Schema(description = "제목. 최대 15자(공백 포함).", example = "권한 요청 플로우 점검") @NotBlank @Size(max = 15)
        String title,
    @Schema(description = "역할", example = "backend", implementation = TaskRole.class) @NotBlank
        String role,
    @Schema(description = "우선순위", example = "medium", implementation = MobilePriority.class)
        @NotBlank
        String priority) {}
