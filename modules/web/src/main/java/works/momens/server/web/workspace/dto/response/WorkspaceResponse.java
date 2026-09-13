package works.momens.server.web.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.workspace.core.WorkspaceDetail;

/**
 * 워크스페이스 한 건의 응답. {@code GET /api/workspaces}의 배열 원소와 {@code GET /api/workspaces/{workspaceId}}(래퍼
 * 없음)가 공유합니다(docs/design/legacy-product-api-migration/slice-workspace-read.md 4.3).
 *
 * <p>{@code description}은 값이 없으면 응답에서 생략합니다(레거시 {@code omitempty} 동작 유지).
 */
@Schema(description = "워크스페이스")
public record WorkspaceResponse(
    @Schema(description = "워크스페이스 식별자", example = "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8") UUID id,
    @Schema(description = "이름", example = "Momens") String name,
    @Schema(description = "slug", example = "momens") String slug,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "설명. 미설정 시 응답에서 생략됩니다.", example = "제품팀 워크스페이스", nullable = true)
        String description,
    @Schema(description = "생성 시각(UTC)", example = "2026-06-27T09:00:00Z") Instant createdAt,
    @Schema(description = "수정 시각(UTC)", example = "2026-06-27T09:00:00Z") Instant updatedAt) {

  public static WorkspaceResponse from(WorkspaceDetail detail) {
    return new WorkspaceResponse(
        detail.id(),
        detail.name(),
        detail.slug(),
        detail.description(),
        detail.createdAt(),
        detail.updatedAt());
  }
}
