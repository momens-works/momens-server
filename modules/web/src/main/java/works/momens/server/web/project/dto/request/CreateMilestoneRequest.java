package works.momens.server.web.project.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.milestone.MilestoneHealthStatus;

@Schema(description = "웹 마일스톤 생성 요청")
public record CreateMilestoneRequest(
    @Schema(description = "마일스톤 이름. 필수 입력값입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
    @Schema(description = "마일스톤 설명") String description,
    @Schema(description = "목표일", format = "date") LocalDate targetDate,
    @Schema(description = "상태. 생략하면 planned가 적용됩니다.", implementation = MilestoneHealthStatus.class)
        String healthStatus,
    @Schema(description = "진행률. 0 이상 100 이하이며, 생략하면 0이 적용됩니다.") Integer progress,
    @Schema(description = "마일스톤 요약") String summary,
    @Schema(description = "마지막 맥락 갱신 시각") Instant lastContextAt,
    @Schema(description = "소유자 식별자 목록. 생략하면 프로젝트 소유자가 적용됩니다.") List<UUID> ownerUserIds) {}
