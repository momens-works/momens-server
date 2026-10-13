package works.momens.server.project.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 프로젝트 health 상태입니다. 같은 문자열을 쓰는 milestone과도 별도 도메인 값 집합으로 관리합니다. */
public enum ProjectHealthStatus {
  ON_TRACK("on_track"),
  AT_RISK("at_risk"),
  BLOCKED("blocked"),
  PLANNED("planned"),
  OPEN("open");

  private final String value;

  ProjectHealthStatus(String value) {
    this.value = value;
  }

  public static Optional<ProjectHealthStatus> from(String value) {
    for (ProjectHealthStatus status : values()) {
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
