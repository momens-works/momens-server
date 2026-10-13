package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 현재 상태를 반전하는 토글이 아니라, 전달받은 완료 상태를 그대로 저장합니다. */
@Schema(description = "완료 기준 완료 상태 변경 요청")
public record ToggleChecklistItemRequest(
    @Schema(description = "완료 여부", example = "true") @NotNull Boolean completed) {}
