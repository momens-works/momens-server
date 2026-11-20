package works.momens.server.project.milestone;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 마일스톤 health 상태입니다. 같은 문자열을 쓰는 project와도 별도 도메인 값 집합으로 관리합니다. */
public enum MilestoneHealthStatus {
  ON_TRACK("on_track"),
  AT_RISK("at_risk"),
  BLOCKED("blocked"),
  PLANNED("planned"),
  OPEN("open");

  private final String value;

  MilestoneHealthStatus(String value) {
    this.value = value;
  }

  public static Optional<MilestoneHealthStatus> from(String value) {
    for (MilestoneHealthStatus status : values()) {
      if (status.value.equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return value;
  }
}
