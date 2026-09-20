package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커밋된 OpenAPI 스냅샷 파일을 읽고, 파일 경로와 스냅샷에 선언된 엔드포인트 목록을 제공합니다.
 *
 * <p>스냅샷을 사용하는 모든 테스트는 이 클래스를 통해 파일을 읽습니다. 파일 경로와 JSON 구조를 해석하는 로직을 한곳에서 관리해 스냅샷 생성 방식이 바뀌더라도 수정
 * 지점이 늘어나지 않도록 합니다.
 *
 * <p>PostgreSQL 컨테이너를 기동하는 {@link OpenApiSnapshotTest}와 파일만 읽는 테스트가 함께 사용하므로 Spring 컨텍스트에 의존하지
 * 않습니다.
 */
final class OpenApiSnapshot {

  private static final String PATH_PROPERTY = "momens.openapi.snapshot.path";

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private OpenApiSnapshot() {}

  /** 스냅샷 파일의 경로를 반환합니다. Gradle이 {@code rootProject} 기준의 절대 경로를 주입하므로 실행 위치에 영향을 받지 않습니다. */
  static Path path() {
    String configured = System.getProperty(PATH_PROPERTY);
    assertThat(configured)
        .as("%s 시스템 프로퍼티가 필요합니다. Gradle test task 설정을 확인하세요.", PATH_PROPERTY)
        .isNotBlank();
    return Path.of(configured);
  }

  /** 스냅샷의 {@code paths}에 선언된 모든 엔드포인트를 반환합니다. */
  static List<OpenApiOperation> operations() throws IOException {
    JsonNode paths = JsonMapper.builder().build().readTree(Files.readString(path())).path("paths");
    List<OpenApiOperation> operations = new ArrayList<>();
    for (Map.Entry<String, JsonNode> pathItem : paths.properties()) {
      for (Map.Entry<String, JsonNode> entry : pathItem.getValue().properties()) {
        if (HTTP_METHODS.contains(entry.getKey())) {
          operations.add(
              new OpenApiOperation(
                  pathItem.getKey(),
                  entry.getKey(),
                  entry.getValue().path("operationId").asString()));
        }
      }
    }
    return operations;
  }
}
