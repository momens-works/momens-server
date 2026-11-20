package works.momens.server.web.project.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.milestone.MilestoneDetail;

@Schema(description = "웹 마일스톤 응답입니다. 수정 충돌 확인용 버전을 포함합니다.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebMilestoneResponse(
    UUID id,
    UUID projectId,
    String name,
    String description,
    LocalDate targetDate,
    String status,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> ownerUserIds,
    String healthStatus,
    int progress,
    String summary,
    Instant lastContextAt,
    Instant createdAt,
    Instant updatedAt,
    @Schema(description = "수정 충돌 확인용 버전", minimum = "0") long version) {

  public static WebMilestoneResponse from(MilestoneDetail milestone) {
    return new WebMilestoneResponse(
        milestone.id(),
        milestone.projectId(),
        milestone.name(),
        milestone.description(),
        milestone.targetDate(),
        milestone.status(),
        milestone.ownerUserIds(),
        milestone.healthStatus(),
        milestone.progress(),
        milestone.summary(),
        milestone.lastContextAt(),
        milestone.createdAt(),
        milestone.updatedAt(),
        milestone.version());
  }
}
