package works.momens.server.mobile.bootstrap;

import java.util.List;
import java.util.UUID;
import works.momens.server.user.UserProfile;

/**
 * bootstrap 조합 결과 읽기 모델. presentation 응답 DTO와 분리하며, 컨트롤러가 응답 shape로 매핑합니다.
 *
 * <p>{@code defaultProjectId}는 접근 가능한 project가 없으면 null입니다(0개 응답은 2026-07-04 가결정, 기획 확인 후 확정).
 */
public record BootstrapContext(
    UserProfile me,
    UUID defaultProjectId,
    List<AccessibleProject> projects,
    boolean onboardingCompleted) {

  /** 접근 가능한 project 한 건. {@code role}은 소속 workspace 멤버십 role입니다. */
  public record AccessibleProject(UUID id, String name, String role) {}
}
