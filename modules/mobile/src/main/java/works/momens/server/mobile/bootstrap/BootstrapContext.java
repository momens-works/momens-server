package works.momens.server.mobile.bootstrap;

import java.util.List;
import java.util.UUID;
import works.momens.server.user.UserProfile;

/** bootstrap 조합 결과 읽기 모델. presentation 응답 DTO와 분리하며, 컨트롤러가 응답 shape로 매핑합니다. */
public record BootstrapContext(
    UserProfile me,
    UUID defaultProjectId,
    List<AccessibleProject> projects,
    boolean onboardingCompleted) {

  public record AccessibleProject(UUID id, String name, String role) {}
}
