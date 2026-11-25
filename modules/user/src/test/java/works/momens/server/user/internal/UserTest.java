package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

  @Test
  void completeMobileOnboardingKeepsFirstCompletedAt() {
    User user = User.builder().email("onboarding@momens.works").name("onboarding").build();

    boolean first = user.completeMobileOnboarding();
    Instant firstCompletedAt = user.getMobileOnboardingCompletedAt();
    boolean second = user.completeMobileOnboarding();

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(user.getMobileOnboardingCompletedAt()).isSameAs(firstCompletedAt);
  }
}
