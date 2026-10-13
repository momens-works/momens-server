package works.momens.server.signal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SignalType {
  DECISION("decision"),
  RISK("risk"),
  QUESTION("question"),
  CHANGE("change");

  private final String value;

  SignalType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
