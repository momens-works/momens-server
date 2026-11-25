package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;
import works.momens.server.common.api.ExpectedVersionDeserializer;

@Schema(description = "완료 기준 완료 상태 변경 요청")
public record ToggleChecklistItemRequest(
    @Schema(description = "저장할 완료 상태. 현재 값을 반전하지 않고 이 값으로 저장합니다.", example = "true") @NotNull
        Boolean completed,
    @Schema(
            description = "조회한 작업 버전. 생략하거나 null이면 요청 처리 중의 동시 변경만 검사합니다.",
            minimum = "0",
            nullable = true)
        @JsonDeserialize(using = ExpectedVersionDeserializer.class)
        Long version) {}
