package works.momens.server.mobile.appupdate;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모바일 앱에서 전달한 버전 문자열을 비교 가능한 값으로 표현합니다.
 *
 * <p>점으로 구분된 숫자를 1개부터 3개까지 허용하며, 생략된 자리는 0으로 간주합니다. 따라서 {@code 1.0}과 {@code 1.0.0}은 같은 버전입니다. 각 자리는
 * 정수로 비교하므로 {@code 1.10}은 {@code 1.9}보다 높은 버전입니다.
 *
 * <p>앞에 0이 붙은 값({@code 01})과 접미사({@code -beta}, {@code +5})는 허용하지 않습니다. 정수 범위를 초과하지 않도록 각 자리는 최대
 * 9자리까지 허용합니다. 형식에 맞지 않는 문자열을 전달하면 {@link #from(String)}은 빈 {@link Optional}을 반환합니다.
 */
record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

  private static final Pattern FORMAT =
      Pattern.compile("(0|[1-9]\\d{0,8})(?:\\.(0|[1-9]\\d{0,8}))?(?:\\.(0|[1-9]\\d{0,8}))?");

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
