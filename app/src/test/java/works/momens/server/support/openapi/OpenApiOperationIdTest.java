package works.momens.server.support.openapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 엔드포인트에 {@code operationId}가 명시되어 있고 ADR-0021의 명명 규칙을 준수하는지 검증합니다.
 *
 * <p>{@code operationId}를 지정하지 않으면 springdoc이 메서드명을 기준으로 이름을 자동 생성합니다. 이름이 중복되면 뒤에 {@code _1}을
 * 붙입니다. 이 번호는 컨트롤러 탐색 순서에 따라 달라지므로, 엔드포인트 하나를 추가한 PR에서 관련 없는 엔드포인트의 이름까지 변경될 수 있습니다.
 *
 * <ul>
 *   <li>선언 및 형식: {@code @Operation}이 선언된 모든 메서드에 {@code operationId}가 지정되어 있고, 값이 camelCase 형식인지
 *       ArchUnit으로 검증합니다.
 *   <li>스냅샷 대조: 커밋된 스냅샷의 모든 {@code operationId}가 애너테이션에 명시된 값의 집합에 포함되는지 검증합니다. 이를 통해 docs
 *       interface 없이 추가된 엔드포인트의 자동 생성 이름과, 명시한 이름이 중복되어 springdoc이 붙인 {@code _N} 형식의 이름을 감지합니다.
 *   <li>접두사: 경로가 클라이언트 구분 세그먼트로 시작하면 {@code operationId}도 해당 접두사로 시작해야 합니다. 반대로 해당 세그먼트가 없는 경로에서는
 *       해당 접두사를 사용할 수 없습니다. 이는 ADR-0021의 규칙 3에 해당합니다.
 * </ul>
 *
 * <p>이 테스트는 Spring 컨텍스트를 기동하지 않습니다. ArchUnit으로 클래스를 한 번만 읽어 세 검사에서 공유하며, 스냅샷은 {@link
 * OpenApiSnapshotFile}이 가리키는 파일에서 읽습니다. {@code DO_NOT_INCLUDE_JARS}를 적용하면 다른 Gradle 모듈에 있는 {@code
 * *ControllerDocs}가 검사 대상에서 제외되므로 해당 옵션은 사용하지 않습니다.
 */
class OpenApiOperationIdTest {

  private static final String BASE_PACKAGE = "works.momens.server";

  private static final Pattern OPERATION_ID_PATTERN = Pattern.compile("^[a-z][A-Za-z0-9]*$");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private static final Map<String, String> CLIENT_PREFIX_BY_PATH =
      Map.of(
          "/api/mobile/", "mobile",
          "/api/auth/web/", "web",
          "/api/dev/", "dev");

  private static final Pattern CLIENT_PREFIX_PATTERN = Pattern.compile("^(mobile|web|dev)[A-Z]");

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE_PACKAGE);

  private record SnapshotOperation(String path, String method, String operationId) {}

  @Test
  void everyOperationDeclaresOperationId() {
    methods()
        .that()
        .areAnnotatedWith(Operation.class)
        .should(declareWellFormedOperationId())
        .check(CLASSES);
  }

  @Test
  void snapshotOperationIdsAreDeclared() throws IOException {
    Set<String> declared = declaredOperationIds();

    List<SnapshotOperation> undeclared =
        snapshotOperations().stream()
            .filter(operation -> !declared.contains(operation.operationId()))
            .toList();

    assertThat(undeclared)
        .as("스냅샷의 operationId가 @Operation에 명시된 값과 일치하지 않습니다. 자동 생성된 이름이거나 명시한 이름이 중복되었는지 확인하세요.")
        .isEmpty();
  }

  @Test
  void operationIdPrefixFollowsClientPath() throws IOException {
    List<String> violations = new ArrayList<>();
    for (SnapshotOperation operation : snapshotOperations()) {
      Optional<String> expectedPrefix = clientPrefixOf(operation.path());
      String operationId = operation.operationId();
      boolean hasClientPrefix = CLIENT_PREFIX_PATTERN.matcher(operationId).find();
      if (expectedPrefix.isPresent()) {
        if (!operationId.startsWith(expectedPrefix.get())
            || operationId.length() <= expectedPrefix.get().length()
            || !Character.isUpperCase(operationId.charAt(expectedPrefix.get().length()))) {
          violations.add(operation + " must start with '" + expectedPrefix.get() + "'");
        }
      } else if (hasClientPrefix) {
        violations.add(operation + " must not start with a client prefix");
      }
    }

    assertThat(violations).as("operationId의 접두사가 ADR-0021의 규칙 3을 준수하지 않습니다.").isEmpty();
  }

  private static ArchCondition<JavaMethod> declareWellFormedOperationId() {
    return new ArchCondition<>("declare an operationId matching " + OPERATION_ID_PATTERN) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String operationId = method.getAnnotationOfType(Operation.class).operationId();
        boolean satisfied = OPERATION_ID_PATTERN.matcher(operationId).matches();
        events.add(
            new SimpleConditionEvent(
                method,
                satisfied,
                method.getFullName() + " has operationId '" + operationId + "'"));
      }
    };
  }

  private static Set<String> declaredOperationIds() {
    return CLASSES.stream()
        .map(JavaClass::getMethods)
        .flatMap(Set::stream)
        .map(method -> method.tryGetAnnotationOfType(Operation.class))
        .flatMap(Optional::stream)
        .map(Operation::operationId)
        .filter(operationId -> !operationId.isBlank())
        .collect(Collectors.toSet());
  }

  private static List<SnapshotOperation> snapshotOperations() throws IOException {
    JsonNode paths =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(OpenApiSnapshotFile.path()))
            .path("paths");
    List<SnapshotOperation> operations = new ArrayList<>();
    for (Map.Entry<String, JsonNode> pathItem : paths.properties()) {
      for (Map.Entry<String, JsonNode> entry : pathItem.getValue().properties()) {
        if (HTTP_METHODS.contains(entry.getKey())) {
          operations.add(
              new SnapshotOperation(
                  pathItem.getKey(),
                  entry.getKey(),
                  entry.getValue().path("operationId").asString()));
        }
      }
    }
    return operations;
  }

  private static Optional<String> clientPrefixOf(String path) {
    return CLIENT_PREFIX_BY_PATH.entrySet().stream()
        .filter(entry -> path.startsWith(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst();
  }
}
