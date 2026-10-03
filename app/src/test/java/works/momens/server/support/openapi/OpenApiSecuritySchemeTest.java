package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * OpenApiConfig가 선언한 Bearer 스킴이 {@code /v3/api-docs}에 실제로 들어가는지 검증한다. springdoc 버전에 따라 개별
 * operation에서 {@code @SecurityRequirements}(빈 값)로 전역 요구를 지우는 동작이 달라질 수 있어(MOM-83 설계 검토), 생성된 문서로 직접
 * 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSecuritySchemeTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;

  private JsonNode apiDocs() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new ObjectMapper().readTree(body);
  }

  @Test
  void declaresBearerJwtSecurityScheme() throws Exception {
    JsonNode scheme = apiDocs().path("components").path("securitySchemes").path("bearerAuth");

    assertThat(scheme.isMissingNode()).as("bearerAuth scheme must be declared").isFalse();
    assertThat(scheme.path("type").asString()).isEqualTo("http");
    assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
    assertThat(scheme.path("bearerFormat").asString()).isEqualTo("JWT");
  }

  @Test
  void appliesBearerRequirementGlobally() throws Exception {
    JsonNode security = apiDocs().path("security");

    assertThat(security.isArray()).isTrue();
    boolean requiresBearer = false;
    for (JsonNode requirement : security) {
      if (requirement.has("bearerAuth")) {
        requiresBearer = true;
      }
    }
    assertThat(requiresBearer).as("global security must require bearerAuth").isTrue();
  }

  @Test
  void exemptsPublicAuthEndpointsFromSecurity() throws Exception {
    JsonNode paths = apiDocs().path("paths");

    // 공개 엔드포인트는 모바일 인증 3개(POST), 웹 인증 4개(로그인과 콜백은 GET, 세션 갱신과 로그아웃은 POST), 모바일 앱 업데이트 확인
    // 1개(GET)입니다.
    Map<String, String> publicEndpoints =
        Map.of(
            "/api/auth/google/token", "post",
            "/api/auth/refresh", "post",
            "/api/auth/logout", "post",
            "/api/auth/google/login", "get",
            "/api/auth/google/callback", "get",
            "/api/auth/web/refresh", "post",
            "/api/auth/web/logout", "post",
            "/api/mobile/app-update", "get");

    for (Map.Entry<String, String> endpoint : publicEndpoints.entrySet()) {
      JsonNode security = paths.path(endpoint.getKey()).path(endpoint.getValue()).path("security");
      assertThat(security.isArray())
          .as(endpoint.getKey() + " must override global security")
          .isTrue();
      assertThat(security)
          .as(endpoint.getKey() + " must have empty security so no padlock is shown")
          .isEmpty();
    }
  }

  @Test
  void protectedEndpointInheritsGlobalSecurity() throws Exception {
    JsonNode operation = apiDocs().path("paths").path("/api/me").path("get");

    assertThat(operation.isMissingNode()).as("/api/me GET must be documented").isFalse();
    assertThat(operation.path("security").isMissingNode())
        .as("protected endpoint inherits global security instead of overriding it")
        .isTrue();
  }
}
