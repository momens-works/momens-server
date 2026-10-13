package works.momens.server.auth.internal.security;

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
 * <p>문서 관련 경로는 모든 환경에서 인증 없이 접근할 수 있도록 설정하지만, 핸들러의 등록 여부는 {@code springdoc.api-docs.enabled} 설정에 따라
 * 달라집니다. 공용 {@code SecurityConfig}의 {@code PUBLIC_PATHS}에는 항상 등록되는 공개 경로만 선언하므로, 문서 관련 공개 경로는 분리해 이
 * 클래스에서 관리합니다.
 *
 * <p>전용 {@code SecurityFilterChain}에는 등록 조건을 적용하지 않습니다. 문서 제공을 비활성화한 환경에서도 요청은 인증 없이 통과하지만, 해당 핸들러가
 * 없어 404를 반환합니다.
 *
 * <p>세 경로는 ADR-0006에 따라 모두 {@code /api} 접두사 아래에 둡니다. 경로는 {@code application.yml}의 {@code
 * springdoc.api-docs.path}와 {@code springdoc.swagger-ui.path}에서 설정하며, Swagger UI 정적 리소스는 후자에서 파생된
 * 경로 아래에 제공됩니다.
 */
@Configuration
class SpringDocSecurityConfig {

  @Bean
  @Order(0)
  SecurityFilterChain springDocSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v3/api-docs/**", "/api/swagger-ui.html", "/api/swagger-ui/**")
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
