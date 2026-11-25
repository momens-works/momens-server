package works.momens.server.mobile.appupdate;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모바일 앱에서 전달한 버전 문자열을 비교 가능한 값으로 표현합니다. 각 자리를 9자리로 제한해 정수 범위를 넘지 않게 합니다. */
record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

  static final String FORMAT_REGEX =
      "^(0|[1-9]\\d{0,8})(?:\\.(0|[1-9]\\d{0,8}))?(?:\\.(0|[1-9]\\d{0,8}))?$";

  private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

  private static final Comparator<AppVersion> ORDER =
      Comparator.comparingInt(AppVersion::major)
          .thenComparingInt(AppVersion::minor)
          .thenComparingInt(AppVersion::patch);

  static Optional<AppVersion> from(String value) {
    if (value == null) {
      return Optional.empty();
    }
    Matcher matcher = FORMAT.matcher(value);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new AppVersion(part(matcher.group(1)), part(matcher.group(2)), part(matcher.group(3))));
  }

  boolean isOlderThan(AppVersion other) {
    return compareTo(other) < 0;
  }

  @Override
  public int compareTo(AppVersion other) {
    return ORDER.compare(this, other);
  }

  private static int part(String digits) {
    return digits == null ? 0 : Integer.parseInt(digits);
  }
}
