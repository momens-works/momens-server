package works.momens.server.web.decision.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import tools.jackson.databind.annotation.JsonDeserialize;
import works.momens.server.project.decision.DecisionReversibility;

@Schema(description = "Decision 생성 요청")
public record CreateDecisionRequest(
    @NotEmpty @Schema(description = "제목", example = "PostgreSQL 사용") String title,
    @NotEmpty @Schema(description = "결정 배경", example = "트랜잭션 일관성이 필요합니다.") String context,
    @Schema(description = "대안. 생략하거나 빈 문자열이면 저장하지 않습니다.", example = "MySQL", nullable = true)
        String alternatives,
    @NotEmpty @Schema(description = "결정 근거", example = "현재 운영 환경과 호환됩니다.") String rationale,
    @JsonDeserialize(using = DecisionReversibilityDeserializer.class)
        @Schema(
            description = "가역성은 reversible 또는 irreversible입니다. 생략·null·빈 문자열이면 reversible입니다.",
            example = "reversible",
            implementation = String.class,
            pattern = "^(reversible|irreversible|)$",
            nullable = true)
        DecisionReversibility reversibility) {}
