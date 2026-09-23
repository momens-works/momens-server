package works.momens.server.support.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.support.persistence.CheckConstraintEnumLinks.ColumnWithoutEnum;
import works.momens.server.support.persistence.CheckConstraintEnumLinks.EnumLink;

/**
 * CHECK 제약이 허용하는 값과 연결된 enum의 저장 값이 일치하는지 검증합니다.
 *
 * <p>같은 값 집합이 Flyway 마이그레이션과 Java enum에 각각 선언되어 있어 기존에는 한쪽만 수정해도 불일치를 발견할 수 없었습니다. {@code
 * ddl-auto=validate}는 매핑된 컬럼의 존재 여부와 타입만 검증하고 CHECK 제약은 검증하지 않으므로 별도의 검증이 필요합니다.
 *
 * <p>CHECK 제약과 enum의 대응 관계는 {@link CheckConstraintEnumLinks}에 선언하고, 이 테스트는 해당 선언을 실제 스키마와 대조합니다.
 *
 * <p>허용되지 않는 값을 직접 저장하는 대신 CHECK 제약의 정의를 조회합니다. 값을 저장하는 방식으로는 DB가 해당 값을 거부한다는 사실만 확인할 수 있으며, enum에
 * 그 값이 없다는 사실은 확인할 수 없기 때문입니다.
 *
 * <p>검증 대상은 이 레포지토리의 마이그레이션으로 구성한 test 스키마입니다. 레거시가 생성한 테이블은 운영 스키마와 이 레포지토리의 재구성 결과가 다를 수 있으며, 해당
 * 차이는 이 테스트의 검증 범위에 포함되지 않습니다.
 */
@SpringBootTest
class CheckConstraintEnumConsistencyTest extends AbstractPostgresIntegrationTest {

  // information_schema.check_constraints 대신 pg_constraint를 조회합니다.
  // information_schema는 NOT NULL을 CHECK 제약으로 변환해 함께 반환하고 테이블 이름 컬럼이 없어
  // 별도 조인이 필요하며, 여러 컬럼에 걸친 제약은 조인 결과가 중복됩니다.
  // PostgreSQL 공식 문서도 제약 정의를 추출할 때 pg_get_constraintdef 사용을 권장합니다.
  private static final String CHECK_CONSTRAINTS =
      """
      SELECT r.relname AS table_name,
             c.conname AS constraint_name,
             pg_get_constraintdef(c.oid) AS definition,
             array_length(c.conkey, 1) AS column_count,
             (SELECT a.attname
                FROM pg_attribute a
               WHERE a.attrelid = c.conrelid AND a.attnum = c.conkey[1]) AS first_column
        FROM pg_constraint c
        JOIN pg_class r ON r.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = r.relnamespace
       WHERE c.contype = 'c'
         AND n.nspname = 'public'
         AND r.relname <> 'flyway_schema_history'
       ORDER BY 1, 2
      """;

  // IN 조건은 허용 값이 여러 개이면 = ANY (ARRAY[...]) 형태로,
  // 하나이면 = '값' 형태로 저장됩니다.
  // 한 가지 형태만 해석하면 허용 값이 하나인 제약이 검증 대상에서 누락됩니다.
  private static final Pattern ANY_ARRAY =
      Pattern.compile("^CHECK \\(\\(\\w+ = ANY \\(ARRAY\\[(.+)]\\)\\)\\)$");

  private static final Pattern SINGLE_EQUALS =
      Pattern.compile("^CHECK \\(\\(\\w+ = '(.*)'::\\w+\\)\\)$");

  private static final Pattern ARRAY_CONTAINS = Pattern.compile("\\b\\w+\\s+<@\\s+ARRAY\\[(.+?)]");

  private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'::\\w+");

  @Autowired private JdbcClient jdbcClient;

  private List<ValueLimitingConstraint> valueLimitingConstraints;

  private List<String> unrecognizedConstraints;

  private record ValueLimitingConstraint(
      String table, String column, String constraintName, Set<String> allowedValues) {

    String qualifiedColumn() {
      return table + "." + column;
    }
  }

  @BeforeEach
  void readConstraints() {
    List<Map<String, Object>> rows = jdbcClient.sql(CHECK_CONSTRAINTS).query().listOfRows();
    assertThat(rows).as("public 스키마에서 CHECK 제약을 찾지 못했습니다. Flyway가 실행되었는지 확인하세요.").isNotEmpty();

    valueLimitingConstraints = new ArrayList<>();
    unrecognizedConstraints = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if ((Integer) row.get("column_count") > 1) {
        continue;
      }
      String definition = (String) row.get("definition");
      Set<String> allowedValues = allowedValues(definition);
      if (allowedValues.isEmpty()) {
        if (definition.contains("= ANY (ARRAY[")
            || definition.contains("<@ ARRAY[")
            || definition.matches(".*= '.*'::\\w+.*")) {
          unrecognizedConstraints.add(row.get("constraint_name") + ": " + definition);
        }
        continue;
      }
      valueLimitingConstraints.add(
          new ValueLimitingConstraint(
              (String) row.get("table_name"),
              (String) row.get("first_column"),
              (String) row.get("constraint_name"),
              allowedValues));
    }
  }

  @Test
  @DisplayName("값 집합을 제한하는 CHECK 제약은 모두 형식을 해석할 수 있다")
  void constraintShapesAreRecognized() {
    assertThat(unrecognizedConstraints)
        .as("형식을 해석할 수 없는 CHECK 제약이 있습니다. 대응 관계를 선언하거나 대조하지 않는 근거를 작성하세요.")
        .isEmpty();
  }

  @Test
  @DisplayName("값 집합을 제한하는 CHECK 제약과 선언 목록은 일대일로 대응한다")
  void constraintInventoryMatchesDeclarations() {
    Set<String> declared = new LinkedHashSet<>();
    List<String> problems = new ArrayList<>();
    for (EnumLink link : CheckConstraintEnumLinks.ENUM_LINKS) {
      declared.add(link.table() + "." + link.column());
    }
    for (ColumnWithoutEnum column : CheckConstraintEnumLinks.COLUMNS_WITHOUT_ENUM) {
      String qualified = column.table() + "." + column.column();
      if (!declared.add(qualified)) {
        problems.add("같은 컬럼이 중복 선언되었습니다: " + qualified);
      }
    }

    Set<String> actual = new LinkedHashSet<>();
    for (ValueLimitingConstraint constraint : valueLimitingConstraints) {
      if (!actual.add(constraint.qualifiedColumn())) {
        problems.add(
            "같은 컬럼에 값 집합을 제한하는 CHECK 제약이 둘 이상 있습니다: "
                + constraint.qualifiedColumn()
                + " ("
                + constraint.constraintName()
                + ")");
      }
    }
    actual.stream()
        .filter(column -> !declared.contains(column))
        .forEach(column -> problems.add("선언되지 않은 CHECK 제약입니다: " + column));
    declared.stream()
        .filter(column -> !actual.contains(column))
        .forEach(column -> problems.add("어떤 CHECK 제약과도 연결되지 않는 선언입니다: " + column));

    assertThat(problems).as("CHECK 제약 목록과 선언이 일치하지 않습니다.").isEmpty();
  }

  @Test
  @DisplayName("연결된 CHECK 제약과 enum의 값 집합 차이는 선언과 일치한다")
  void linkedEnumsMatchConstraintValues() {
    Map<String, ValueLimitingConstraint> byColumn = new LinkedHashMap<>();
    valueLimitingConstraints.forEach(
        constraint -> byColumn.put(constraint.qualifiedColumn(), constraint));

    List<String> problems = new ArrayList<>();
    for (EnumLink link : CheckConstraintEnumLinks.ENUM_LINKS) {
      ValueLimitingConstraint constraint = byColumn.get(link.table() + "." + link.column());
      if (constraint == null) {
        continue;
      }
      Set<String> enumValues = link.storedValues().values();
      Set<String> constraintOnly = new TreeSet<>(constraint.allowedValues());
      constraintOnly.removeAll(enumValues);
      Set<String> enumOnly = new TreeSet<>(enumValues);
      enumOnly.removeAll(constraint.allowedValues());

      if (!constraintOnly.equals(new TreeSet<>(link.difference().constraintOnly()))) {
        problems.add(
            difference(
                "CHECK 제약에만 있는 값이 선언과 다릅니다: ",
                link,
                constraintOnly,
                link.difference().constraintOnly()));
      }
      if (!enumOnly.equals(new TreeSet<>(link.difference().enumOnly()))) {
        problems.add(
            difference("enum에만 있는 값이 선언과 다릅니다: ", link, enumOnly, link.difference().enumOnly()));
      }
    }
    assertThat(problems).as("CHECK 제약과 enum의 값 집합 차이가 선언과 일치하지 않습니다.").isEmpty();
  }

  private static String difference(
      String prefix, EnumLink link, Set<String> actual, Set<String> declared) {
    return prefix
        + link.table()
        + "."
        + link.column()
        + " ("
        + link.storedValues().enumName()
        + ", 실제 "
        + new TreeSet<>(actual)
        + ", 선언 "
        + new TreeSet<>(declared)
        + ")";
  }

  /**
   * CHECK 제약 정의에서 허용 값 집합을 추출합니다.
   *
   * <p>값 집합을 제한하는 형식이 아니면 빈 집합을 반환합니다. 호출하는 쪽에서 검증 대상이 아닌 제약과 형식 해석에 실패한 제약을 구분해 처리합니다.
   */
  private static Set<String> allowedValues(String definition) {
    Matcher anyArray = ANY_ARRAY.matcher(definition);
    if (anyArray.matches()) {
      Set<String> values = new LinkedHashSet<>();
      Matcher literal = LITERAL.matcher(anyArray.group(1));
      while (literal.find()) {
        values.add(literal.group(1).replace("''", "'"));
      }
      return values;
    }
    Matcher singleEquals = SINGLE_EQUALS.matcher(definition);
    if (singleEquals.matches()) {
      return Set.of(singleEquals.group(1).replace("''", "'"));
    }
    Matcher arrayContains = ARRAY_CONTAINS.matcher(definition);
    if (arrayContains.find()) {
      Set<String> values = new LinkedHashSet<>();
      Matcher literal = LITERAL.matcher(arrayContains.group(1));
      while (literal.find()) {
        values.add(literal.group(1).replace("''", "'"));
      }
      return values;
    }
    return Set.of();
  }
}
