package works.momens.server.project.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 프로젝트의 {@code health_status} 값입니다.
 *
 * <p>저장할 수 있는 값은 {@code projects.health_status}의 CHECK 제약에서 허용하는 다섯 가지 값과 같습니다. milestone도 현재 같은
 * 문자열 집합을 사용하지만 별도 하위 도메인이므로 자기 경계 안에서 독립적으로 관리합니다.
 *
 * <p>프로젝트 기본값은 {@code open}이며 엔티티 생성자에서 결정합니다.
 *
 * <p>enum 상수 이름과 DB 저장값이 다르므로 {@code value}를 별도로 관리합니다. {@code name()}을 저장값으로 사용하면 enum 상수 이름을 변경할
 * 때 DB에 저장되는 값도 함께 달라질 수 있습니다.
 *
 * <p>web 모듈에서 요청 문서의 값 목록을 이 enum을 기준으로 생성하므로 공개합니다. 요청 값에 대한 검증은 기존과 같이 이 모듈의 생성 구현에서 담당합니다.
 */
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
