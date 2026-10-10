package works.momens.server.auth.internal.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * springdoc에서 제공하는 OpenAPI 문서와 Swagger UI 경로의 공개 설정을 관리합니다.
 *
 * <p>각 경로의 등록 조건에 맞춰 공개 설정을 관리합니다.
 *
 * <p>springdoc은 OpenAPI 문서와 Swagger UI의 등록 조건이 다르므로 {@code SecurityFilterChain}을 두 개로 분리합니다. {@code
 * SpringDocConfiguration}은 {@code springdoc.api-docs.enabled}만 확인하지만, Swagger UI를 등록하는 {@code
 * SwaggerConfig}는 {@code springdoc.swagger-ui.enabled}와 {@code SpringDocConfiguration} 빈의 존재 여부를 함께
 * 확인합니다.
 *
 * <p>세 경로는 ADR-0006에 따라 모두 {@code /api} 접두사 아래에 둡니다. 경로는 {@code application.yml}의 {@code
 * springdoc.api-docs.path}와 {@code springdoc.swagger-ui.path}에서 설정하며, Swagger UI 정적 리소스는 후자에서 파생된
 * 경로 아래에 제공됩니다.
 */
@Configuration
class SpringDocSecurityConfig {

  /**
   * OpenAPI 문서 경로의 공개 설정을 담당하는 {@code SecurityFilterChain}입니다. springdoc의 OpenAPI 문서 제공이 활성화된 경우에만
   * 등록하며, OAuth2 Resource Server를 적용하지 않아 인증 없이 문서에 접근할 수 있도록 합니다.
   */
  @Bean
  @Order(0)
  @ConditionalOnProperty(
      name = "springdoc.api-docs.enabled",
      havingValue = "true",
      matchIfMissing = true)
  SecurityFilterChain apiDocsSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v3/api-docs/**")
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  /**
   * Swagger UI 경로의 공개 설정을 담당하는 {@code SecurityFilterChain}입니다. Swagger UI 진입 문서와 정적 리소스에 인증 없이 접근할
   * 수 있도록 설정하며, springdoc의 OpenAPI 문서와 Swagger UI가 모두 활성화된 경우에만 등록합니다.
   */
  @Bean
  @Order(0)
  @ConditionalOnProperty(
      name = {"springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"},
      havingValue = "true",
      matchIfMissing = true)
  SecurityFilterChain swaggerUiSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/swagger-ui.html", "/api/swagger-ui/**")
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
