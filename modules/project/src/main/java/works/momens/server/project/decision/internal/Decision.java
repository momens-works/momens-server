package works.momens.server.project.decision.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.decision.CreateDecisionCommand;
import works.momens.server.project.decision.DecisionDetail;
import works.momens.server.project.decision.DecisionReversibility;

@Getter
@Entity
@Table(name = "decisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Decision extends BaseEntity {
  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(nullable = false, columnDefinition = "text")
  private String title;

  @Column(nullable = false, columnDefinition = "text")
  private String context;

  @Column(columnDefinition = "text")
  private String alternatives;

  @Column(nullable = false, columnDefinition = "text")
  private String rationale;

  @Column(nullable = false, columnDefinition = "text")
  private String reversibility;

  @Column(name = "decision_maker", nullable = false)
  private UUID decisionMaker;

  Decision(CreateDecisionCommand command) {
    this.projectId = command.projectId();
    this.decisionMaker = command.decisionMaker();
    this.title = required(command.title(), "title");
    this.context = required(command.context(), "context");
    this.rationale = required(command.rationale(), "rationale");
    this.alternatives =
        command.alternatives() == null || command.alternatives().isEmpty()
            ? null
            : command.alternatives();
    this.reversibility =
        (command.reversibility() == null
                ? DecisionReversibility.REVERSIBLE
                : command.reversibility())
            .value();
  }

  DecisionDetail toDetail() {
    return new DecisionDetail(
        getId(),
        projectId,
        title,
        context,
        alternatives,
        rationale,
        reversibility,
        decisionMaker,
        getCreatedAt(),
        getUpdatedAt());
  }

  private static String required(String value, String field) {
    if (value == null || value.isEmpty()) {
      throw FieldValidationException.forField(field);
    }
    return value;
  }
}
