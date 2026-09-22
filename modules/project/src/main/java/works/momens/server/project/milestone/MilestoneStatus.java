package works.momens.server.project.milestone;

import java.util.Optional;

/** 마일스톤의 {@code status} 값입니다. */
public enum MilestoneStatus {
  PLANNED("planned"),
  ACTIVE("active"),
  COMPLETED("completed"),
  MISSED("missed");

  private final String value;

  MilestoneStatus(String value) {
    this.value = value;
  }

  public static Optional<MilestoneStatus> from(String value) {
    for (MilestoneStatus status : values()) {
      if (status.value.equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  public String value() {
    return value;
  }
}
