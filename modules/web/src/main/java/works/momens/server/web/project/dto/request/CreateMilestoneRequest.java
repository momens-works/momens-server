package works.momens.server.web.project.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.milestone.MilestoneHealthStatus;

@Schema(description = "웹 마일스톤 생성 요청")
public record CreateMilestoneRequest(
    @Schema(description = "마일스톤 이름", requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(description = "마일스톤 설명") String description,
    @Schema(description = "목표 날짜", format = "date") LocalDate targetDate,
    @Schema(description = "상태", defaultValue = "planned") MilestoneHealthStatus healthStatus,
    @Schema(description = "진행률", minimum = "0", maximum = "100", defaultValue = "0")
        Integer progress,
    @Schema(description = "마일스톤 요약") String summary,
    @Schema(description = "마지막 맥락 갱신 시각") Instant lastContextAt,
    @Schema(description = "소유자 식별자 목록. 생략하면 요청자가 소유자로 지정됩니다. 동일한 식별자를 중복으로 전달하면 400을 반환합니다.")
        List<UUID> ownerUserIds) {}
