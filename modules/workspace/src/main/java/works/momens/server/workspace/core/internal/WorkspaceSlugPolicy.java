package works.momens.server.workspace.core.internal;

import java.util.Set;
import java.util.function.Predicate;

/** 조회·생성·수정이 공유하는 slug 규칙입니다. 이름에서 생성한 slug와 사용자가 직접 입력한 slug의 정규화 규칙은 다릅니다. */
final class WorkspaceSlugPolicy {

  static final int MAX_LENGTH = 63;
  static final int MIN_LENGTH = 2;
  static final String FALLBACK = "workspace";

  private static final Set<String> RESERVED =
      Set.of(
          "admin",
          "api",
          "app",
          "auth",
          "blockers",
          "decisions",
          "health",
          "invite",
          "login",
          "logout",
          "memories",
          "memory-candidates",
          "milestones",
          "minsu",
          "onboarding",
          "projects",
          "settings",
          "source-connections",
          "source-refs",
          "tasks",
          "welcome",
          "workspaces");

  private WorkspaceSlugPolicy() {}

  /**
   * 앞뒤 공백만 제거합니다. 대문자를 소문자로 변환하지 않는 것이 레거시 동작입니다.
   *
   * <p>제거 대상은 {@code Character.isWhitespace}가 {@code true}인 문자입니다. 한글 입력기에서 입력되는 넓은 공백({@code
   * U+3000})은 제거되지만, {@code U+00A0} 공백은 이 기준에 해당하지 않아 유지됩니다. 레거시 Go의 {@code strings.TrimSpace} 구현을
   * 그대로 재현하는 것이 아니라 이 기준을 공백 제거 규칙으로 사용합니다.
   */
  static String normalize(String rawSlug) {
    return rawSlug == null ? "" : rawSlug.strip();
  }

  static boolean isValid(String slug) {
    if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
      return false;
    }
    if (slug.charAt(0) == '-' || slug.charAt(slug.length() - 1) == '-') {
      return false;
    }
    boolean lastWasDash = false;
    for (int i = 0; i < slug.length(); i++) {
      char c = slug.charAt(i);
      if (c == '-') {
        if (lastWasDash) {
          return false;
        }
        lastWasDash = true;
      } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        lastWasDash = false;
      } else {
        return false;
      }
    }
    return true;
  }

  static boolean isReserved(String slug) {
    return RESERVED.contains(slug);
  }

  /**
   * 워크스페이스 이름으로부터 slug를 생성합니다.
   *
   * <p>이름을 소문자로 변환한 뒤 영문자와 숫자를 제외한 문자를 하이픈으로 바꾸고, 연속된 하이픈은 하나로 합칩니다. 앞뒤 하이픈을 제거하고 최대 길이를 초과하면 잘라 낸
   * 뒤 앞뒤 하이픈을 다시 제거합니다. 사용할 수 있는 문자가 남지 않으면 {@code workspace}를 반환합니다.
   */
  static String slugify(String name) {
    StringBuilder builder = new StringBuilder();
    boolean lastWasDash = false;
    for (int i = 0; i < name.length(); i++) {
      char c = Character.toLowerCase(name.charAt(i));
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        builder.append(c);
        lastWasDash = false;
      } else if (!lastWasDash) {
        builder.append('-');
        lastWasDash = true;
      }
    }
    String slug = trimDashes(builder.toString());
    if (slug.length() > MAX_LENGTH) {
      slug = trimDashes(slug.substring(0, MAX_LENGTH));
    }
    return slug.isEmpty() ? FALLBACK : slug;
  }

  /**
   * 이미 사용 중인 slug를 기준으로 다음 후보를 찾습니다.
   *
   * <p>첫 번째 후보는 base 자체이며, 두 번째 후보부터 {@code -2}, {@code -3}을 차례로 붙입니다. {@code -1}은 사용하지 않습니다. 후보가
   * 형식 규칙을 충족하지 않거나 예약어에 해당하면 건너뛰고 다음 번호를 확인합니다. 접미사를 붙였을 때 최대 길이를 초과하면 base의 뒷부분을 잘라 접미사가 들어갈 자리를
   * 확보합니다.
   */
  static String nextAvailable(String base, Predicate<String> exists) {
    String stem = base.isEmpty() ? FALLBACK : base;
    for (int n = 1; ; n++) {
      String candidate = n == 1 ? stem : withSuffix(stem, n);
      if (!isValid(candidate) || isReserved(candidate)) {
        continue;
      }
      if (!exists.test(candidate)) {
        return candidate;
      }
    }
  }

  private static String withSuffix(String base, int n) {
    String suffix = "-" + n;
    int stemLength = MAX_LENGTH - suffix.length();
    if (stemLength < 1) {
      return FALLBACK;
    }
    String stem = trimDashes(base);
    if (stem.length() > stemLength) {
      stem = trimDashes(stem.substring(0, stemLength));
    }
    return (stem.isEmpty() ? FALLBACK : stem) + suffix;
  }

  private static String trimDashes(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '-') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == '-') {
      end--;
    }
    return value.substring(start, end);
  }
}
