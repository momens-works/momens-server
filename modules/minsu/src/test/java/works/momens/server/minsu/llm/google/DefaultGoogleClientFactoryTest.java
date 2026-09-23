package works.momens.server.minsu.llm.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.HttpOptions;
import com.google.genai.types.Schema;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.momens.server.minsu.llm.LlmResponseField;

class DefaultGoogleClientFactoryTest {

  @Test
  void usesStableV1WithEightSecondTimeoutAndOneTotalAttempt() {
    HttpOptions options = DefaultGoogleClientFactory.httpOptions(Duration.ofSeconds(8));

    assertThat(options.apiVersion()).contains("v1");
    assertThat(options.timeout()).contains(8_000);
    assertThat(options.retryOptions()).isPresent();
    assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
  }

  @Test
  void structuredOutputSchemaHasRequiredClosedFieldsWithoutUnsupportedMaxLength() {
    Schema schema =
        DefaultGoogleSdkClient.responseSchema(
            List.of(
                new LlmResponseField("title", null, List.of()),
                new LlmResponseField("role", null, List.of("pm", "design", "backend", "frontend")),
                new LlmResponseField("priority", null, List.of("low", "medium", "high"))));

    assertThat(schema.required()).contains(List.of("title", "role", "priority"));
    assertThat(schema.propertyOrdering()).contains(List.of("title", "role", "priority"));
    assertThat(schema.properties()).isPresent();
    assertThat(schema.properties().orElseThrow().keySet())
        .containsExactlyInAnyOrder("title", "role", "priority");
    assertThat(schema.properties().orElseThrow().get("title").maxLength()).isEmpty();
    assertThat(schema.properties().orElseThrow().get("role").enum_())
        .contains(List.of("pm", "design", "backend", "frontend"));
    assertThat(schema.properties().orElseThrow().get("priority").enum_())
        .contains(List.of("low", "medium", "high"));
  }
}
