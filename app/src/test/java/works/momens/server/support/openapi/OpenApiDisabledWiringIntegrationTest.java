package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * springdoc을 끈 배선(운영 프로필의 {@code springdoc.api-docs.enabled: false})에서 컨텍스트가 로드되는지 검증합니다.
 *
 * <p>{@link OpenApiConfig}의 {@code modelResolver}가 springdoc 자동 구성이 제공하는 {@code
 * ObjectMapperProvider}를 주입받으므로, 이 설정을 조건 없이 등록하면 문서를 끈 환경에서 컨텍스트 생성이 실패합니다. 운영에서만 성립하는 조합이라
 * local·test 배선으로는 드러나지 않았습니다(MOM-0924).
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=false")
@AutoConfigureMockMvc
class OpenApiDisabledWiringIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private ApplicationContext context;

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("springdoc 비활성이 OpenAPI 설정을 통째로 빼고 컨텍스트는 정상 로드된다")
  void dropsOpenApiConfigWhenApiDocsDisabled() {
    assertThat(context.containsBean("openAPI")).isFalse();
    assertThat(context.containsBean("modelResolver")).isFalse();
    assertThat(context.containsBean("swaggerOperationCustomizer")).isFalse();
    assertThat(context.containsBean("nullableAsTypeCustomizer")).isFalse();
  }

  @Test
  @DisplayName("OpenAPI 문서 제공을 비활성화하면 문서 경로 요청에 401이 아닌 404를 반환합니다.")
  void answersNotFoundOnApiDocsPathWhenApiDocsDisabled() throws Exception {
    mvc.perform(get("/api/v3/api-docs")).andExpect(status().isNotFound());
  }
}
