package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * OpenAPI 문서와 Swagger UI가 {@code /api} 접두사 아래에서 제공되는지 검증합니다.
 *
 * <p>ADR-0006에 따라 ingress는 {@code /api} 접두사가 붙은 요청만 신규 서버로 전달합니다. 문서 경로가 이 접두사를 벗어나면 dev 환경에서 접근할 수
 * 없으므로, {@code /api} 접두사 규칙을 검증합니다.
 *
 * <p>경로는 {@link Environment}에서 읽어옵니다. {@code application.yml}의 경로 설정과 {@code
 * SpringDocSecurityConfig}의 공개 설정이 일치하지 않으면 인증이 요구되어 테스트가 실패합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiPathIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String API_PREFIX = "/api/";

  private static final String API_DOCS_PATH_PROPERTY = "springdoc.api-docs.path";

  private static final String SWAGGER_UI_PATH_PROPERTY = "springdoc.swagger-ui.path";

  private static final String LEGACY_API_DOCS_PATH = "/v3/api-docs";

  @Autowired private MockMvc mvc;

  @Autowired private Environment environment;

  @Test
  @DisplayName("OpenAPI 문서와 Swagger UI 경로는 모두 `/api` 접두사 아래에 선언됩니다.")
  void declaresDocumentPathsUnderApiPrefix() {
    assertThat(environment.getProperty(API_DOCS_PATH_PROPERTY)).startsWith(API_PREFIX);
    assertThat(environment.getProperty(SWAGGER_UI_PATH_PROPERTY)).startsWith(API_PREFIX);
  }

  @Test
  @DisplayName("Swagger UI 진입 요청은 `/api` 아래의 진입 문서로 리다이렉트되며, 정적 리소스도 같은 접두사 아래에서 제공됩니다.")
  void servesSwaggerUiEntryAndStaticResourceUnderApiPrefix() throws Exception {
    String entryPath = environment.getProperty(SWAGGER_UI_PATH_PROPERTY);

    String indexPath =
        mvc.perform(get(entryPath))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();

    assertThat(indexPath).startsWith(API_PREFIX).endsWith("/index.html");
    mvc.perform(get(indexPath)).andExpect(status().isOk());
    mvc.perform(get(indexPath.replace("/index.html", "/swagger-ui.css")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("이전 OpenAPI 문서 경로는 인증 없이 접근할 수 없습니다.")
  void rejectsLegacyApiDocsPathWithoutAuthentication() throws Exception {
    mvc.perform(get(LEGACY_API_DOCS_PATH)).andExpect(status().isUnauthorized());
  }
}
