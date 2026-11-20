package works.momens.server.support.openapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
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

  @Bean
  public OpenApiCustomizer apiVersionDefaultCustomizer() {
    // springdoc 3.2.0부터 API-Version 헤더 스키마에 default가 자동 생성되지 않으므로,
    // 이 커스터마이저에서 기본값을 1로 직접 설정합니다.
    return openApi ->
        openApi.getPaths().values().stream()
            .flatMap(path -> path.readOperations().stream())
            .map(operation -> operation.getParameters())
            .filter(parameters -> parameters != null)
            .flatMap(List::stream)
            .filter(parameter ->
                "API-Version".equals(parameter.getName()) && "header".equals(parameter.getIn()))
            .map(Parameter::getSchema)
            .forEach(schema -> schema.setDefault("1"));
  }

  /**
   * 런타임 wire format은 {@code spring.jackson.property-naming-strategy=SNAKE_CASE}(application.yml)가
   * 전역으로 정하지만, 스키마를 만드는 swagger-core는 Jackson 2라 Boot의 Jackson 3 설정을 읽지 못해 스키마 property가 Java
   * 필드명(camelCase)으로 나온다. swagger-core가 introspection에 쓰는 Jackson 2 ObjectMapper에 같은 snake_case 전략을
   * 걸어, 런타임 규칙과 동일하게 스키마 property를 전역 snake_case로 생성한다. 특정 필드만 다르게 두려면 {@code @JsonProperty}로 개별
   * override할 수 있다(런타임 Jackson 3와 swagger-core Jackson 2가 같은 애노테이션 패키지를 읽는다).
   *
   * <p>springdoc은 기본 {@code ModelResolver}를 이 빈으로 교체하므로, 해당 빈도 OpenAPI 3.1 모드로 생성합니다.
   */
  @Bean
  public ModelResolver modelResolver(ObjectMapperProvider objectMapperProvider) {
    return new ModelResolver(
            objectMapperProvider
                .jsonMapper()
                .copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
        .openapi31(true);
  }
}
