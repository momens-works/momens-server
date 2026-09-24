package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiRequestExampleTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;

  /**
   * swagger-annotations와 swagger-core의 버전이 다르면 {@code @Schema}의 기본값 sentinel을 잘못 해석해 request
   * schema에 {@code "default": null}을 만든다. Swagger UI는 이를 우선해 request body 예시 전체를 {@code null}로
   * 그리므로, 대표 request schema로 의존성 버전 정렬을 검증한다.
   */
  @Test
  void requestSchemaDoesNotCarrySpuriousNullDefault() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = new ObjectMapper().readTree(body);
    assertThat(root.path("openapi").asString()).startsWith("3.1.");

    JsonNode schema = root.path("components").path("schemas").path("GoogleTokenRequest");
    assertThat(schema.isMissingNode()).isFalse();
    assertThat(schema.has("default"))
        .as(
            "default must be absent; default: null makes Swagger UI render the request example as null")
        .isFalse();

    JsonNode apiVersion =
        findParameter(
            root.path("paths").path("/api/auth/google/token").path("post"), "API-Version");
    assertThat(apiVersion).as("Spring MVC API versioning should document API-Version").isNotNull();
    assertThat(apiVersion.path("in").asString()).isEqualTo("header");
    assertThat(apiVersion.path("schema").path("type").asString()).isEqualTo("string");
  }

  @Test
  void apiVersionDefaultMatchesSupportedVersionOnEveryOperation() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode paths = new ObjectMapper().readTree(body).path("paths");
    List<String> mismatches = new ArrayList<>();
    int checked = 0;
    for (Map.Entry<String, JsonNode> path : paths.properties()) {
      if (!path.getKey().startsWith("/api/") || "/api/mcp".equals(path.getKey())) {
        continue;
      }
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        JsonNode apiVersion = findParameter(operation.getValue(), "API-Version");
        assertThat(apiVersion)
            .as("%s %s must document API-Version", operation.getKey(), path.getKey())
            .isNotNull();
        checked++;
        JsonNode schema = apiVersion.path("schema");
        if (!"1".equals(schema.path("default").asString())
            || schema.path("enum").size() != 1
            || !"1".equals(schema.path("enum").get(0).asString())) {
          mismatches.add(path.getKey() + " " + operation.getKey());
        }
      }
    }

    assertThat(checked).isPositive();
    assertThat(mismatches).as("API-Version default must use supported header value 1").isEmpty();
  }

  /**
   * Dev Signal 생성 엔드포인트는 Swagger의 request body에 예시를 제공해 Try it out만으로 바로 호출할 수 있도록 한다.
   *
   * <p>예시가 누락되거나 필드 계약과 달라지면 데모와 수동 테스트를 진행하기 어렵다. 따라서 api-docs에 예시가 포함되는지와 각 필드에 의도한 예시 값이 노출되는지를
   * 확인한다.
   */
  @Test
  void devSignalCreateCarriesRequestExample() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ObjectMapper mapper = new ObjectMapper();
    JsonNode examples =
        mapper
            .readTree(body)
            .path("paths")
            .path("/api/dev/projects/{projectId}/signals")
            .path("post")
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("examples");

    assertThat(examples.isMissingNode()).as("dev signal 생성은 request 예시를 문서화해야 한다").isFalse();

    // springdoc는 example value를 JSON으로 파싱한 뒤 다시 직렬화한다.
    // 문자열 형태는 달라질 수 있으므로 JSON 전체가 아니라 각 필드의 값을 검증한다.
    JsonNode value = examples.properties().iterator().next().getValue().path("value");
    JsonNode json = value.isObject() ? value : mapper.readTree(value.asString());
    assertThat(json.path("type").asString()).isEqualTo("change");
    assertThat(json.path("title").asString()).contains("할인 쿠폰 적용 실패 문의");
    assertThat(json.has("minsu_suggestion")).as("snake_case 필드명이 그대로 나와야 한다").isTrue();
    assertThat(json.path("evidence").size()).as("evidence 3건이 그대로 실려야 한다").isEqualTo(3);
  }

  private JsonNode findParameter(JsonNode operation, String name) {
    for (JsonNode parameter : operation.path("parameters")) {
      if (name.equals(parameter.path("name").asString())) {
        return parameter;
      }
    }
    return null;
  }

  /**
   * 런타임 snake_case를 swagger-core가 따라가지 못해 스키마 property가 camelCase로 나오던 문제를, swagger-core의
   * ObjectMapper에 snake_case 전략을 걸어(전역, {@code OpenApiConfig}) 고쳤다. 새 DTO/필드가 camelCase 키로 새면 실제
   * wire format(snake_case)과 문서가 어긋나므로, 전체 스키마를 스캔해 회귀를 막는다. 런타임 쪽 같은 검증은 {@code
   * OpenApiActualResponseContractTest}가 실제 응답으로 수행한다.
   */
  @Test
  void allSchemaPropertyKeysAreSnakeCase() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode schemas = new ObjectMapper().readTree(body).path("components").path("schemas");
    List<String> offenders = new ArrayList<>();
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      collectCamelCaseKeys(schema.getKey(), schema.getValue(), offenders);
    }

    assertThat(offenders)
        .as("schema property keys must be snake_case to match the wire format")
        .isEmpty();
  }

  private void collectCamelCaseKeys(String path, JsonNode schema, List<String> offenders) {
    for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
      if (property.getKey().chars().anyMatch(Character::isUpperCase)) {
        offenders.add(path + "." + property.getKey());
      }
      collectCamelCaseKeys(path + "." + property.getKey(), property.getValue(), offenders);
    }
    if (schema.has("items")) {
      collectCamelCaseKeys(path + "[]", schema.get("items"), offenders);
    }
  }
}
