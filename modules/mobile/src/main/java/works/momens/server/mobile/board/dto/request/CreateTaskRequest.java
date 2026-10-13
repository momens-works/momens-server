package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

@Schema(description = "일반 작업 생성 요청")
public record CreateTaskRequest(
    @Schema(description = "제목. 최대 15자(공백 포함).", example = "권한 요청 플로우 점검") @NotBlank @Size(max = 15)
        String title,
    @Schema(
            description =
                "업무 역할. pm은 기획과 조율, design은 디자인, backend는 서버, frontend는 웹과 앱 화면 작업을 담당합니다.",
            example = "backend")
        @NotNull
        TaskRole role,
    @Schema(description = "우선순위. urgent도 그대로 저장하며 응답에서는 high로 표시합니다.", example = "medium") @NotNull
        TaskPriority priority) {}
