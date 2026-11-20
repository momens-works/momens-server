package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

class OpenApiVersionDefaultCustomizerTest {

  @Test
  void normalizesExistingDefault() {
    StringSchema schema = new StringSchema()._default("1.0.0");

    customize(schema);

    assertThat(schema.getDefault()).isEqualTo("1");
  }

  @Test
  void fillsAbsentDefault() {
    StringSchema schema = new StringSchema();

    customize(schema);

    assertThat(schema.getDefault()).isEqualTo("1");
  }

  private void customize(StringSchema schema) {
    Parameter parameter = new Parameter().name("API-Version").in("header").schema(schema);
    OpenAPI openApi =
        new OpenAPI()
            .paths(
                new Paths()
                    .addPathItem(
                        "/api/example",
                        new PathItem().get(new Operation().addParametersItem(parameter))));

    new OpenApiConfig().apiVersionDefaultCustomizer().customise(openApi);
  }
}
