package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenApiSchemaNameTest {
  @Test
  void dtoRecordsHaveDistinctSchemaNames() {
    Map<String, List<Class<?>>> names =
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("works.momens.server")
                .stream()
                .map(javaClass -> javaClass.reflect())
                .filter(
                    type ->
                        type.getPackageName().contains(".dto.")
                            && (type.isRecord() || type.isAnnotationPresent(Schema.class)))
                .collect(Collectors.groupingBy(OpenApiSchemaNameTest::schemaName));

    assertThat(names.entrySet().stream().filter(entry -> entry.getValue().size() > 1).toList())
        .as("DTO record의 OpenAPI 스키마 이름이 중복됩니다")
        .isEmpty();
  }

  private static String schemaName(Class<?> type) {
    Schema schema = type.getAnnotation(Schema.class);
    return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
  }
}
