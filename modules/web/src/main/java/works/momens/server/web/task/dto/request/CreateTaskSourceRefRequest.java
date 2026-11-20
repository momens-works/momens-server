package works.momens.server.web.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "작업에 첨부할 링크 요청")
public record CreateTaskSourceRefRequest(
    @Schema(
            description = "첨부할 주소. 앞뒤 공백을 제거한 뒤 비어 있으면 400으로 응답합니다.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String sourceUrl,
    @Schema(description = "링크 종류. 지원하는 값이 아니거나 생략하면 LINK로 저장합니다.") String sourceType,
    @Schema(description = "링크 제목. 생략하거나 비어 있으면 저장하지 않습니다.") String title) {}
