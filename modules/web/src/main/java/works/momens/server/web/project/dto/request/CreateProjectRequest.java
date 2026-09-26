package works.momens.server.web.project.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.core.ProjectHealthStatus;

@Schema(description = "웹 프로젝트 생성 요청")
public record CreateProjectRequest(
    @Schema(description = "프로젝트 이름. 필수 입력값입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
    @Schema(description = "프로젝트 설명") String description,
    @Schema(description = "목표일", format = "date") LocalDate targetDate,
    @Schema(description = "상태. 생략하면 open이 적용됩니다.") ProjectHealthStatus healthStatus,
    @Schema(description = "진행률. 0 이상 100 이하이며, 생략하면 0이 적용됩니다.") Integer progress,
    @Schema(description = "프로젝트 요약") String summary,
    @Schema(description = "미해결 항목 수. 0 이상이며, 생략하면 0이 적용됩니다.") Integer unresolvedCount,
    @Schema(description = "VOC 신호 수. 0 이상이며, 생략하면 0이 적용됩니다.") Integer vocSignalCount,
    @Schema(description = "마지막 맥락 갱신 시각") Instant lastContextAt,
    @Schema(description = "소유자 식별자 목록. 생략하면 요청자가 단독 소유자로 지정됩니다. 동일한 식별자를 중복으로 전달하면 400을 반환합니다.")
        List<UUID> ownerUserIds) {}
