package works.momens.server.mobile.appupdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppUpdatePolicyTest {

  private final AppUpdatePolicy policy =
      new AppUpdatePolicy(
          Map.of(
              AppPlatform.ANDROID, new AppVersion(1, 2, 0),
              AppPlatform.IOS, new AppVersion(2, 0, 0)));

  @Test
  void requiresUpdateOnlyBelowMinimumVersionOfPlatform() {
    assertThat(policy.isUpdateRequired(AppPlatform.ANDROID, new AppVersion(1, 1, 9))).isTrue();
    assertThat(policy.isUpdateRequired(AppPlatform.ANDROID, new AppVersion(1, 2, 0))).isFalse();
    assertThat(policy.isUpdateRequired(AppPlatform.ANDROID, new AppVersion(1, 10, 0))).isFalse();
    assertThat(policy.isUpdateRequired(AppPlatform.IOS, new AppVersion(1, 10, 0))).isTrue();
  }

  @Test
  void rejectsMissingMinimumVersionOfAnyPlatform() {
    assertThatThrownBy(
            () -> new AppUpdatePolicy(Map.of(AppPlatform.ANDROID, new AppVersion(1, 0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
