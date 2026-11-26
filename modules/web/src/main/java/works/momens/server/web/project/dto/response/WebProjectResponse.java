package works.momens.server.web.project.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.project.core.ProjectDetail;

@Schema(description = "웹 프로젝트 응답")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebProjectResponse(
    UUID id,
    UUID workspaceId,
    String label,
    String name,
    String description,
    String status,
    UUID ownerId,
    List<UUID> ownerUserIds,
    LocalDate targetDate,
    String healthStatus,
    String summary,
    int unresolvedCount,
    int vocSignalCount,
    Instant lastContextAt,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {

  public static WebProjectResponse from(ProjectDetail project) {
    return new WebProjectResponse(
        project.id(),
        project.workspaceId(),
        project.label(),
        project.name(),
        project.description(),
        project.status(),
        project.ownerId(),
        project.ownerUserIds(),
        project.targetDate(),
        project.healthStatus(),
        project.summary(),
        project.unresolvedCount(),
        project.vocSignalCount(),
        project.lastContextAt(),
        project.metadata(),
        project.createdAt(),
        project.updatedAt());
  }
}
