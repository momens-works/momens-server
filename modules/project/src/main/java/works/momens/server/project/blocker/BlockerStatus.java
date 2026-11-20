package works.momens.server.project.blocker;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BlockerStatus {
  ACTIVE("active"),
  RESOLVED("resolved");

  private final String value;

  BlockerStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
