package works.momens.server.project.milestone.internal;

import java.util.Optional;

/** 마일스톤의 {@code status} 값입니다. {@code milestones.status} CHECK 제약과 같은 값 집합을 소유합니다. */
enum MilestoneStatus {
  PLANNED("planned"),
  ACTIVE("active"),
  COMPLETED("completed"),
  MISSED("missed");

  private final String value;

  MilestoneStatus(String value) {
    this.value = value;
  }

  static Optional<MilestoneStatus> from(String value) {
    for (MilestoneStatus status : values()) {
      if (status.value.equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  String value() {
    return value;
  }
}
