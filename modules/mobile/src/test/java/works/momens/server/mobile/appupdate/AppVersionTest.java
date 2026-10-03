package works.momens.server.mobile.appupdate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AppVersionTest {

  @Test
  void treatsOmittedPartsAsZero() {
    assertThat(AppVersion.from("1")).contains(new AppVersion(1, 0, 0));
    assertThat(AppVersion.from("1.2")).contains(new AppVersion(1, 2, 0));
    assertThat(AppVersion.from("1.2.3")).contains(new AppVersion(1, 2, 3));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"1.0.0.0", "1.0-beta", "1.0.0+5", "01.0", "1..0", "a.b", " 1.0", "1234567890"})
  void rejectsMalformedVersion(String value) {
    assertThat(AppVersion.from(value)).isEmpty();
  }

  @Test
  void comparesEachPartAsNumber() {
    AppVersion older = AppVersion.from("1.9").orElseThrow();
    AppVersion newer = AppVersion.from("1.10").orElseThrow();

    assertThat(older.isOlderThan(newer)).isTrue();
    assertThat(newer.isOlderThan(older)).isFalse();
    assertThat(AppVersion.from("1.0").orElseThrow().isOlderThan(new AppVersion(1, 0, 0))).isFalse();
  }
}
