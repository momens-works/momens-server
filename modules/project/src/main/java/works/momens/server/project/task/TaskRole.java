package works.momens.server.project.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 태스크를 담당하는 직군입니다.
 *
 * <p>`tasks.role`의 CHECK 제약에서 허용하는 네 가지 값을 표현합니다. minsu 모듈의 `Role`은 LLM 출력에 사용하는 값의 범위를 정의하며, 저장할
 * 때는 이 enum으로 변환합니다.
 */
public enum TaskRole {
  PM("pm"),
  DESIGN("design"),
  BACKEND("backend"),
  FRONTEND("frontend");

  private final String value;

  TaskRole(String value) {
    this.value = value;
  }

  public static Optional<TaskRole> from(String value) {
    for (TaskRole constant : values()) {
      if (constant.value.equals(value)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return value;
  }
}
