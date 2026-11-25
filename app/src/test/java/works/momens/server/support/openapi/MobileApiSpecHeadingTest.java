package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 엔드포인트를 삭제하거나 경로를 바꾼 뒤 모바일 API 명세에 그 엔드포인트의 절이 남지 않게 합니다. */
class MobileApiSpecHeadingTest {

  private static final String PATH_PROPERTY = "momens.mobile-api-spec.path";

  private static final Pattern ENDPOINT_HEADING =
      Pattern.compile("^###\\s+(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)\\s*$");

  @Test
  void endpointHeadingsReferToSnapshotOperations() throws IOException {
    Set<String> operations =
        OpenApiSnapshot.operations().stream()
            .map(operation -> operation.method().toUpperCase(Locale.ROOT) + " " + operation.path())
            .collect(Collectors.toSet());

    List<String> headings =
        Files.readAllLines(specPath()).stream()
            .map(ENDPOINT_HEADING::matcher)
            .filter(Matcher::matches)
            .map(matcher -> matcher.group(1) + " " + matcher.group(2))
            .toList();

    assertThat(headings).isNotEmpty();
    assertThat(headings)
        .filteredOn(heading -> !operations.contains(heading))
        .as("`### METHOD /path` 절은 OpenAPI 스냅샷에 있는 엔드포인트만 가리켜야 합니다.")
        .isEmpty();
  }

  private static Path specPath() {
    String configured = System.getProperty(PATH_PROPERTY);
    assertThat(configured)
        .as("%s 시스템 프로퍼티가 필요합니다. Gradle test task 설정을 확인하세요.", PATH_PROPERTY)
        .isNotBlank();
    return Path.of(configured);
  }
}
