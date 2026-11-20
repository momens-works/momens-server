package works.momens.server.web.decision.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.project.decision.DecisionDetail;

@Schema(description = "생성된 Decision. 성공 응답은 wrapper 없이 반환합니다.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebDecisionResponse(
    UUID id,
    UUID projectId,
    String title,
    String context,
    String alternatives,
    String rationale,
    @Schema(allowableValues = {"reversible", "irreversible"}) String reversibility,
    UUID decisionMaker,
    Instant createdAt,
    Instant updatedAt) {
  public static WebDecisionResponse from(DecisionDetail decision) {
    return new WebDecisionResponse(
        decision.id(),
        decision.projectId(),
        decision.title(),
        decision.context(),
        decision.alternatives(),
        decision.rationale(),
        decision.reversibility(),
        decision.decisionMaker(),
        decision.createdAt(),
        decision.updatedAt());
  }
}
