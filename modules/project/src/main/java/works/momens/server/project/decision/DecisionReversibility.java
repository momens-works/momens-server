package works.momens.server.project.decision;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DecisionReversibility {
  REVERSIBLE("reversible"),
  IRREVERSIBLE("irreversible");

  private final String value;

  DecisionReversibility(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
