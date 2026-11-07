package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 스냅샷 일치만으로는 서로 다른 DTO가 하나의 스키마로 합쳐진 오류를 감지할 수 없다. */
class OpenApiSchemaNameTest {
  @Test
  @DisplayName("API 모델의 OpenAPI 스키마 이름은 중복되지 않는다")
  void apiModelsHaveDistinctSchemaNames() {
    Map<String, List<Class<?>>> names =
        MainSourceClasses.imported().stream()
            .map(javaClass -> javaClass.reflect())
            .filter(
                type ->
                    type.isAnnotationPresent(Schema.class)
                        || (type.getPackageName().contains(".dto.") && type.isRecord()))
            .collect(Collectors.groupingBy(OpenApiSchemaNameTest::schemaName));

    assertThat(names.entrySet().stream().filter(entry -> entry.getValue().size() > 1).toList())
        .as("API 모델의 OpenAPI 스키마 이름이 중복됩니다")
        .isEmpty();
  }

  private static String schemaName(Class<?> type) {
    Schema schema = type.getAnnotation(Schema.class);
    return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
  }
}
