package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 완료기준 항목 완료 상태 변경 요청. 형식은 docs/spec/openapi.json에서 확인합니다.
 *
 * <p>완료 상태를 그대로 지정하는 값이라 항상 채워야 합니다.
 */
@Schema(description = "완료 기준 완료 상태 변경 요청")
public record ToggleChecklistItemRequest(
    @Schema(description = "완료 여부", example = "true") @NotNull Boolean completed) {}
