package works.momens.server.workspace.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** slug 규칙이 레거시 {@code internal/workspace/slug.go}와 동일하게 동작하는지 검증합니다. */
class WorkspaceSlugPolicyTest {

  @Test
  @DisplayName("앞뒤 공백만 제거하고 대소문자는 유지한다")
  void normalizeTrimsWhitespaceOnlyAndKeepsCase() {
    assertThat(WorkspaceSlugPolicy.normalize("  Momens  ")).isEqualTo("Momens");
    assertThat(WorkspaceSlugPolicy.normalize(null)).isEmpty();
  }

  @Test
  @DisplayName("앞뒤의 U+3000 공백은 제거하고 U+00A0 공백은 유지한다")
  void normalizeRemovesIdeographicSpaceButKeepsNonBreakingSpace() {
    assertThat(WorkspaceSlugPolicy.normalize("\u3000momens\u3000")).isEqualTo("momens");
    assertThat(WorkspaceSlugPolicy.normalize("\u00A0momens")).isEqualTo("\u00A0momens");
  }

  @Test
  @DisplayName("대문자를 소문자로 변환하지 않으므로 대문자가 포함된 slug는 형식 오류로 판정한다")
  void uppercaseSlugIsInvalidBecauseNormalizeDoesNotLowercase() {
    assertThat(WorkspaceSlugPolicy.isValid("Momens")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("momens")).isTrue();
  }

  @Test
  @DisplayName("slug 길이는 2자 이상 63자 이하여야 한다")
  void slugLengthMustBeBetweenTwoAndSixtyThree() {
    assertThat(WorkspaceSlugPolicy.isValid("a")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("ab")).isTrue();
    assertThat(WorkspaceSlugPolicy.isValid("a".repeat(63))).isTrue();
    assertThat(WorkspaceSlugPolicy.isValid("a".repeat(64))).isFalse();
  }

  @Test
  @DisplayName("하이픈은 처음이나 끝에 올 수 없고 연속해서 사용할 수 없다")
  void dashesMayNotLeadTrailOrRepeat() {
    assertThat(WorkspaceSlugPolicy.isValid("-momens")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("momens-")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("mo--mens")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("mo-mens")).isTrue();
  }

  @Test
  @DisplayName("소문자, 숫자, 하이픈만 사용할 수 있다")
  void onlyLowercaseLettersDigitsAndDashesAreAllowed() {
    assertThat(WorkspaceSlugPolicy.isValid("momens1")).isTrue();
    assertThat(WorkspaceSlugPolicy.isValid("momens_1")).isFalse();
    assertThat(WorkspaceSlugPolicy.isValid("모먼스")).isFalse();
  }

  @Test
  @DisplayName("예약어 목록은 레거시와 동일하다")
  void reservedSlugsMatchLegacyList() {
    assertThat(WorkspaceSlugPolicy.isReserved("admin")).isTrue();
    assertThat(WorkspaceSlugPolicy.isReserved("workspaces")).isTrue();
    assertThat(WorkspaceSlugPolicy.isReserved("momens")).isFalse();
  }

  @Test
  @DisplayName("이름을 소문자로 변환하고 구분자는 하나의 하이픈으로 합친다")
  void slugifyLowercasesAndCollapsesSeparators() {
    assertThat(WorkspaceSlugPolicy.slugify("Momens Works")).isEqualTo("momens-works");
    assertThat(WorkspaceSlugPolicy.slugify("  Momens   Works  ")).isEqualTo("momens-works");
    assertThat(WorkspaceSlugPolicy.slugify("Momens_Works!!")).isEqualTo("momens-works");
  }

  @Test
  @DisplayName("사용할 수 있는 문자가 남지 않으면 workspace를 반환한다")
  void slugifyFallsBackWhenNothingUsableRemains() {
    assertThat(WorkspaceSlugPolicy.slugify("모먼스")).isEqualTo("workspace");
    assertThat(WorkspaceSlugPolicy.slugify("")).isEqualTo("workspace");
  }

  @Test
  @DisplayName("최대 길이에 맞춰 자른 뒤 끝에 남은 하이픈을 제거한다")
  void slugifyTruncatesToMaxLengthThenTrimsTrailingDash() {
    String name = "a".repeat(62) + " b";
    assertThat(WorkspaceSlugPolicy.slugify(name)).isEqualTo("a".repeat(62));
  }

  @Test
  @DisplayName("잘린 위치에 하이픈이 없으면 63자를 그대로 유지한다")
  void slugifyKeepsSixtyThreeCharactersWhenNoDashAtTheCut() {
    assertThat(WorkspaceSlugPolicy.slugify("a".repeat(70))).isEqualTo("a".repeat(63));
  }

  @Test
  @DisplayName("사용 중이 아니면 base를 그대로 반환한다")
  void nextAvailableReturnsBaseWhenFree() {
    assertThat(WorkspaceSlugPolicy.nextAvailable("momens", slug -> false)).isEqualTo("momens");
  }

  @Test
  @DisplayName("다음 후보의 접미사는 1이 아니라 2부터 시작한다")
  void nextAvailableCountsFromTwo() {
    Set<String> taken = Set.of("momens", "momens-2");
    assertThat(WorkspaceSlugPolicy.nextAvailable("momens", taken::contains)).isEqualTo("momens-3");
  }

  @Test
  @DisplayName("base가 예약어이면 건너뛰고 다음 번호를 사용한다")
  void nextAvailableSkipsReservedBase() {
    assertThat(WorkspaceSlugPolicy.nextAvailable("admin", slug -> false)).isEqualTo("admin-2");
  }

  @Test
  @DisplayName("base가 비어 있으면 workspace부터 시작한다")
  void nextAvailableFallsBackWhenBaseIsEmpty() {
    assertThat(WorkspaceSlugPolicy.nextAvailable("", slug -> false)).isEqualTo("workspace");
  }

  @Test
  @DisplayName("접미사가 들어갈 자리를 확보하기 위해 base의 뒷부분을 자른다")
  void nextAvailableTruncatesStemToFitSuffix() {
    String base = "a".repeat(63);
    assertThat(WorkspaceSlugPolicy.nextAvailable(base, slug -> slug.equals(base)))
        .isEqualTo("a".repeat(61) + "-2");
  }
}
