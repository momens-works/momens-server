package works.momens.server.web.blocker.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.project.blocker.BlockerDetail;

@Schema(description = "차단 사유")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlockerResponse(
    UUID id,
    UUID workspaceId,
    String description,
    String status,
    String blockedEntityType,
    UUID blockedEntityId,
    Instant createdAt,
    Instant updatedAt,
    @Schema(description = "해결 시각. 미해결이면 생략합니다.") Instant resolvedAt) {
  public static BlockerResponse from(BlockerDetail detail) {
    return new BlockerResponse(
        detail.id(),
        detail.workspaceId(),
        detail.description(),
        detail.status(),
        detail.blockedEntityType(),
        detail.blockedEntityId(),
        detail.createdAt(),
        detail.updatedAt(),
        detail.resolvedAt());
  }
}
