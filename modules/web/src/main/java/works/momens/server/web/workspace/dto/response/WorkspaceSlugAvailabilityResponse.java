package works.momens.server.web.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import works.momens.server.workspace.core.WorkspaceSlugAvailability;

/**
 * {@code GET /api/workspaces/slug-available} 응답.
 *
 * <p>{@code reason}과 {@code suggestion}은 값이 없으면 응답에서 생략하여 레거시의 {@code omitempty} 동작을 유지합니다.
 */
@Schema(description = "slug 사용 가능 여부")
public record WorkspaceSlugAvailabilityResponse(
    @Schema(description = "앞뒤 공백을 제거한 slug") String slug,
    @Schema(description = "slug를 사용할 수 있는지 여부") boolean available,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
            description = "사용할 수 없는 사유. invalid, reserved, taken 중 하나이며, 사용할 수 있으면 생략됩니다.",
            nullable = true)
        String reason,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "이미 사용 중인 경우 제안하는 대체 slug. 그 밖의 경우에는 생략됩니다.", nullable = true)
        String suggestion) {

  public static WorkspaceSlugAvailabilityResponse from(WorkspaceSlugAvailability availability) {
    return new WorkspaceSlugAvailabilityResponse(
        availability.slug(),
        availability.available(),
        availability.reason() == null ? null : availability.reason().value(),
        availability.suggestion());
  }
}
