package works.momens.server.project.blocker;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BlockedEntityType {
  TASK("task"),
  MILESTONE("milestone");

  private final String value;

  BlockedEntityType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
