package works.momens.server.support.openapi;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 문서(/api/v3/api-docs)와 실제 응답의 계약 대조 테스트.
 *
 * <p>{@code OpenApiRequestExampleTest}는 문서 쪽 스키마만 검사하므로, 문서는 snake_case인데 실제 응답이 camelCase로 어긋나는
 * 드리프트는 잡지 못한다. 여기서는 대표 엔드포인트({@code /api/me})의 실제 request/response를 서버가 내보낸 스펙과
 * openapi-request-validator로 대조하고, 실제 응답 본문 키가 wire format(snake_case)인지 직접 확인해 남은 절반을 막는다. 응답 키 직접
 * 검사를 함께 두는 이유: OpenAPI 스키마는 기본적으로 additional property를 허용하고 응답 필드가 required가 아니므로, validator만으로는 키
 * 이름 드리프트가 통과할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiActualResponseContractTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;

  @Test
  void meResponseMatchesDocumentedContract() throws Exception {
    OpenApiInteractionValidator validator = validatorFromLiveSpec();
    UserProfile user =
        userService.findOrCreate(
            "contract-it@momens.works", "홍길동", "https://cdn.momens.works/avatars/abc.png");
    String token = accessTokens.issueAccessToken(user.id());

    String body =
        mockMvc
            .perform(
                get("/api/me")
                    .header("Authorization", "Bearer " + token)
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(openApi().isValid(validator))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertActualKeysAreSnakeCase(body);
  }

  @Test
  void updateMeRequestAndResponseMatchDocumentedContract() throws Exception {
    OpenApiInteractionValidator validator = validatorFromLiveSpec();
    UserProfile user = userService.findOrCreate("contract-patch-it@momens.works", "홍길동", null);
    String token = accessTokens.issueAccessToken(user.id());

    String body =
        mockMvc
            .perform(
                patch("/api/me")
                    .header("Authorization", "Bearer " + token)
                    .header("API-Version", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"김철수\",\"job_role\":\"Designer\"}"))
            .andExpect(status().isOk())
            .andExpect(openApi().isValid(validator))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertActualKeysAreSnakeCase(body);
  }

  /**
   * bootstrap의 {@code me}는 중첩 record라 springdoc이 {@code /api/me}의 {@code user.MeResponse}와 같은 단순
   * 클래스명 {@code MeResponse}로 스키마를 만들어 충돌했다({@code @Schema(name)}로 분리해 고쳤다). 충돌이 재발하면 문서의 bootstrap
   * {@code me}가 {@code {"user": ...}} shape를 참조하게 되어 실제 응답과 어긋난다. 실제 응답 검증과 함께, 문서의 bootstrap
   * {@code me} 스키마가 실제와 같은 shape인지 직접 확인해 이 회귀를 막는다.
   */
  @Test
  void bootstrapResponseMatchesDocumentedContract() throws Exception {
    String spec = fetchApiDocs();
    OpenApiInteractionValidator validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
    UserProfile user = userService.findOrCreate("bootstrap-contract-it@momens.works", "홍길동", null);
    String token = accessTokens.issueAccessToken(user.id());

    String body =
        mockMvc
            .perform(
                get("/api/mobile/bootstrap")
                    .header("Authorization", "Bearer " + token)
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(openApi().isValid(validator))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertActualKeysAreSnakeCase(body);
    assertThat(new ObjectMapper().readTree(body).path("me").path("email").asString())
        .isEqualTo("bootstrap-contract-it@momens.works");

    JsonNode schemas = new ObjectMapper().readTree(spec).path("components").path("schemas");
    String meSchemaName =
        schemas
            .path("BootstrapResponse")
            .path("properties")
            .path("me")
            .path("$ref")
            .asString()
            .substring("#/components/schemas/".length());
    List<String> meProperties = new ArrayList<>();
    schemas
        .path(meSchemaName)
        .path("properties")
        .properties()
        .forEach(e -> meProperties.add(e.getKey()));
    assertThat(meProperties)
        .as("documented bootstrap me must match the actual shape, not the /api/me MeResponse")
        .contains("id", "email", "name", "avatar_url")
        .doesNotContain("user");
  }

  /** 서버가 실제로 내보내는 스펙과 대조해야 하므로, 고정 파일이 아니라 /api/v3/api-docs에서 스펙을 가져온다. */
  private OpenApiInteractionValidator validatorFromLiveSpec() throws Exception {
    return OpenApiInteractionValidator.createForInlineApiSpecification(fetchApiDocs()).build();
  }

  private String fetchApiDocs() throws Exception {
    return mockMvc
        .perform(get("/api/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void assertActualKeysAreSnakeCase(String body) {
    List<String> offenders = new ArrayList<>();
    collectCamelCaseKeys("$", new ObjectMapper().readTree(body), offenders);
    assertThat(offenders)
        .as("actual response keys must be snake_case to match the documented wire format")
        .isEmpty();
  }

  private void collectCamelCaseKeys(String path, JsonNode node, List<String> offenders) {
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (field.getKey().chars().anyMatch(Character::isUpperCase)) {
        offenders.add(path + "." + field.getKey());
      }
      collectCamelCaseKeys(path + "." + field.getKey(), field.getValue(), offenders);
    }
    if (node.isArray()) {
      for (JsonNode element : node) {
        collectCamelCaseKeys(path + "[]", element, offenders);
      }
    }
  }
}
