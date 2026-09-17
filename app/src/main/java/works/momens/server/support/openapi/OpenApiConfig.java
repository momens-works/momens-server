package works.momens.server.support.openapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 이 클래스의 빈은 전부 springdoc 이 켜져 있을 때만 의미가 있고, modelResolver 는 springdoc
// 자동설정이 만드는 ObjectMapperProvider 를 주입받는다. prod 는 springdoc.api-docs.enabled=false 라
// 그 빈이 없어 조건 없이 등록하면 컨텍스트 생성이 실패한다(MOM-0924). 같은 키로 클래스째 끄면
// 문서 설정과 문서 활성 여부가 한 스위치를 공유한다. 기본값이 켜짐이라 local·test 는 그대로다.
@ConditionalOnProperty(
    name = "springdoc.api-docs.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Configuration
@EnableConfigurationProperties(OpenApiProperties.class)
public class OpenApiConfig {

  /** OpenAPI에서 Bearer 스킴을 가리키는 이름. 스킴 선언과 전역 SecurityRequirement가 같은 이름을 써야 연결되므로 상수로 모은다. */
  private static final String BEARER_SCHEME_NAME = "bearerAuth";

  @Bean
  public OpenAPI openAPI(OpenApiProperties properties) {
    // 런타임은 이미 Bearer 토큰(JWT)으로 보호하지만(SecurityConfig), springdoc이 이 사실을 자동으로 문서에
    // 반영하지 않아 스킴을 직접 선언한다. 전역으로 적용해 모든 operation이 기본적으로 인증을 요구하게 하고(런타임의
    // authenticated()와 맞춘다), 공개 엔드포인트만 @SecurityRequirements(빈 값)로 제외한다(docs/spec/openapi.md,
    // MOM-83).
    return new OpenAPI()
        .info(
            new Info()
                .title("Momens Server API")
                .version("v1")
                .description("Momens Java Spring 제품 API 서버 문서"))
        .servers(List.of(new Server().url(properties.serverUrl()).description("Configured Server")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME_NAME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
  }

  @Bean
  public OperationCustomizer swaggerOperationCustomizer() {
    return new SwaggerOperationCustomizer(
        new ApiExceptionFinder(), new ApiExceptionResolver(), new SwaggerErrorExampleGenerator());
  }

  /**
   * 런타임 wire format은 {@code spring.jackson.property-naming-strategy=SNAKE_CASE}(application.yml)가
   * 전역으로 정하지만, 스키마를 만드는 swagger-core는 Jackson 2라 Boot의 Jackson 3 설정을 읽지 못해 스키마 property가 Java
   * 필드명(camelCase)으로 나온다. swagger-core가 introspection에 쓰는 Jackson 2 ObjectMapper에 같은 snake_case 전략을
   * 걸어, 런타임 규칙과 동일하게 스키마 property를 전역 snake_case로 생성한다. 특정 필드만 다르게 두려면 {@code @JsonProperty}로 개별
   * override할 수 있다(런타임 Jackson 3와 swagger-core Jackson 2가 같은 애노테이션 패키지를 읽는다).
   */
  @Bean
  public ModelResolver modelResolver(ObjectMapperProvider objectMapperProvider) {
    return new ModelResolver(
        objectMapperProvider
            .jsonMapper()
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
  }

  /**
   * {@code @Schema(nullable = true)}는 OpenAPI 3.0의 {@code nullable} 키워드용이라 3.1 직렬화에서 통째로 떨어져, 실제로는
   * null이 오는 필드가 문서에는 null 불허({@code type: string})로 나온다. 3.1의 표현({@code type: [..., "null"]})으로 전역
   * 변환해 문서가 실제 응답과 같은 null 계약을 갖게 한다. 필드별 {@code types = {"string", "null"}} 명시는 누락되기 쉬워 전역 변환으로
   * 통일한다.
   */
  @Bean
  public OpenApiCustomizer nullableAsTypeCustomizer() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      openApi.getComponents().getSchemas().values().forEach(this::applyNullableTypes);
    };
  }

  private void applyNullableTypes(Schema<?> schema) {
    if (Boolean.TRUE.equals(schema.getNullable())) {
      schema.addType("null");
      schema.setNullable(null);
    }
    Map<String, Schema> properties = schema.getProperties();
    if (properties != null) {
      properties.values().forEach(this::applyNullableTypes);
    }
    if (schema.getItems() != null) {
      applyNullableTypes(schema.getItems());
    }
  }
}
