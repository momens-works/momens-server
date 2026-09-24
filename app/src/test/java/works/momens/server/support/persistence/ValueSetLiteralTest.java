package works.momens.server.support.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import works.momens.server.support.persistence.CheckConstraintEnumLinks.EnumLink;
import works.momens.server.support.persistence.ValueSetLiteralAllowlist.AllowedLiteralFile;

/**
 * 컬럼의 허용 값 목록을 도메인 enum이 아닌 곳에서 다시 선언하지 않았는지 검증합니다. 같은 값 집합에 속한 값이 한 파일에 둘 이상 나오면 실패합니다. 링크에 등록된
 * enum 파일과 허용 목록에 등록된 파일만 예외로 처리합니다. 값 하나만 사용하는 경우는 일반적인 단어와 구분하기 어려워 검사하지 않고 리뷰에서 확인합니다. 검사 대상은
 * main의 Java 문자열 리터럴과 resources이며, 테스트 코드와 마이그레이션, docs는 제외합니다.
 */
class ValueSetLiteralTest {

  private static final String ROOT_PROPERTY = "momens.source.root";

  private static Path root;
  private static List<ValueSet> valueSets;
  private static Set<String> linkedEnumFiles;
  private static List<Violation> violations;

  @BeforeAll
  static void scan() throws IOException {
    String configured = System.getProperty(ROOT_PROPERTY);
    assertThat(configured)
        .as("%s 시스템 프로퍼티가 필요합니다. Gradle test task 설정을 확인하세요.", ROOT_PROPERTY)
        .isNotBlank();
    root = Path.of(configured);
    valueSets = valueSets();
    List<Path> sources = mainSources();
    linkedEnumFiles = linkedEnumFiles(sources);
    violations = new ArrayList<>();
    for (Path source : sources) {
      String text = literalText(source);
      for (ValueSet valueSet : valueSets) {
        Set<String> found = valueSet.valuesIn(text);
        if (found.size() >= 2) {
          violations.add(new Violation(relative(source), valueSet, found));
        }
      }
    }
  }

  @Test
  @DisplayName("값 집합은 링크에 등록된 enum과 허용 목록의 파일에서만 선언합니다")
  void valueSetsAreDeclaredOnlyByLinkedEnums() {
    List<String> problems =
        violations.stream()
            .filter(violation -> !linkedEnumFiles.contains(violation.path()))
            .filter(violation -> !allowed(violation))
            .map(Violation::describe)
            .toList();

    assertThat(problems)
        .as("값 집합을 도메인 enum이 아닌 곳에서 다시 선언했습니다. enum을 참조하도록 바꾸거나 허용 목록에 사유를 작성하세요.")
        .isEmpty();
  }

  @Test
  @DisplayName("허용 목록의 모든 항목은 실제로 필요한 예외입니다")
  void allowlistEntriesAreStillNeeded() {
    List<String> problems = new ArrayList<>();
    for (AllowedLiteralFile entry : ValueSetLiteralAllowlist.ALLOWED_LITERAL_FILES) {
      boolean used =
          violations.stream()
              .anyMatch(
                  violation ->
                      violation.path().equals(entry.path())
                          && violation.valueSet().columns().contains(entry.column()));
      if (!used) {
        problems.add(entry.path() + " (" + entry.column() + ")");
      }
    }

    assertThat(problems).as("더 이상 값 집합을 포함하지 않는 파일이 허용 목록에 남아 있습니다.").isEmpty();
  }

  private static boolean allowed(Violation violation) {
    return ValueSetLiteralAllowlist.ALLOWED_LITERAL_FILES.stream()
        .anyMatch(
            entry ->
                entry.path().equals(violation.path())
                    && violation.valueSet().columns().contains(entry.column()));
  }

  /**
   * 링크에서 컬럼별 CHECK 값 집합을 계산하고, 값이 같은 컬럼을 하나의 값 집합으로 묶습니다. 저장 값에 CHECK에만 있는 값을 더하고 enum에만 있는 값을 빼서
   * CHECK 값을 계산하며, 계산 결과가 맞는지는 `CheckConstraintEnumConsistencyTest`에서 DB와 대조해 검증합니다.
   */
  private static List<ValueSet> valueSets() {
    Map<String, Set<String>> byColumn = new LinkedHashMap<>();
    for (EnumLink link : CheckConstraintEnumLinks.ENUM_LINKS) {
      Set<String> values = new TreeSet<>(link.storedValues().values());
      values.addAll(link.difference().constraintOnly());
      values.removeAll(link.difference().enumOnly());
      byColumn.putIfAbsent(link.table() + "." + link.column(), values);
    }
    Map<Set<String>, List<String>> columnsByValues = new LinkedHashMap<>();
    byColumn.forEach(
        (column, values) ->
            columnsByValues.computeIfAbsent(values, key -> new ArrayList<>()).add(column));
    return columnsByValues.entrySet().stream()
        .map(entry -> new ValueSet(entry.getValue(), entry.getKey()))
        .toList();
  }

  private static List<Path> mainSources() throws IOException {
    List<Path> sources = new ArrayList<>();
    for (Path mainDirectory : mainDirectories()) {
      try (Stream<Path> files = Files.walk(mainDirectory)) {
        files
            .filter(Files::isRegularFile)
            .filter(file -> !relative(file).contains("/db/migration/"))
            .sorted()
            .forEach(sources::add);
      }
    }
    return sources;
  }

  private static List<Path> mainDirectories() throws IOException {
    List<Path> directories = new ArrayList<>();
    directories.add(root.resolve("app/src/main"));
    directories.add(root.resolve("common/src/main"));
    try (Stream<Path> modules = Files.list(root.resolve("modules"))) {
      modules
          .map(module -> module.resolve("src/main"))
          .filter(Files::isDirectory)
          .sorted()
          .forEach(directories::add);
    }
    return directories;
  }

  private static Set<String> linkedEnumFiles(List<Path> sources) {
    Set<String> files = new LinkedHashSet<>();
    for (EnumLink link : CheckConstraintEnumLinks.ENUM_LINKS) {
      String suffix =
          "/src/main/java/" + link.storedValues().enumClassName().replace('.', '/') + ".java";
      List<String> matches =
          sources.stream()
              .map(ValueSetLiteralTest::relative)
              .filter(p -> p.endsWith(suffix))
              .toList();
      assertThat(matches)
          .as("%s의 소스 파일을 하나로 특정할 수 없습니다.", link.storedValues().enumClassName())
          .hasSize(1);
      files.add(matches.getFirst());
    }
    return files;
  }

  /** Java 파일은 주석을 제외한 문자열 리터럴만, 그 밖의 파일은 본문 전체를 검사 대상으로 반환합니다. */
  private static String literalText(Path source) throws IOException {
    String text = Files.readString(source, StandardCharsets.UTF_8);
    return source.toString().endsWith(".java") ? JavaLiterals.join(text) : text;
  }

  private static String relative(Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  record ValueSet(List<String> columns, Set<String> values) {

    Set<String> valuesIn(String text) {
      Set<String> found = new TreeSet<>();
      for (String value : values) {
        Pattern token =
            Pattern.compile("(?<![A-Za-z0-9_@-])" + Pattern.quote(value) + "(?![A-Za-z0-9_@-])");
        if (token.matcher(text).find()) {
          found.add(value);
        }
      }
      return found;
    }
  }

  record Violation(String path, ValueSet valueSet, Set<String> found) {

    String describe() {
      return path + " " + valueSet.columns() + " " + found;
    }
  }

  /** Java 소스에서 주석은 건너뛰고 문자열 리터럴과 text block의 내용만 이어 붙입니다. */
  static final class JavaLiterals {

    private JavaLiterals() {}

    static String join(String source) {
      StringBuilder literals = new StringBuilder();
      int i = 0;
      int length = source.length();
      while (i < length) {
        if (source.startsWith("//", i)) {
          int end = source.indexOf('\n', i);
          i = end < 0 ? length : end;
        } else if (source.startsWith("/*", i)) {
          int end = source.indexOf("*/", i + 2);
          i = end < 0 ? length : end + 2;
        } else if (source.startsWith("\"\"\"", i)) {
          int end = source.indexOf("\"\"\"", i + 3);
          while (end > 0 && source.charAt(end - 1) == '\\') {
            end = source.indexOf("\"\"\"", end + 1);
          }
          literals.append(source, i + 3, end).append('\n');
          i = end + 3;
        } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
          char quote = source.charAt(i);
          int end = i + 1;
          while (source.charAt(end) != quote) {
            end += source.charAt(end) == '\\' ? 2 : 1;
          }
          if (quote == '"') {
            literals.append(source, i + 1, end).append('\n');
          }
          i = end + 1;
        } else {
          i++;
        }
      }
      return literals.toString();
    }
  }
}
