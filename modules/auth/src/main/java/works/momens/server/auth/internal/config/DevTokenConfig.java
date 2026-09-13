package works.momens.server.auth.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import works.momens.server.common.config.DevOnly;

/**
 * dev 전용 토큰 발급 엔드포인트의 설정과 보안 체인. {@link DevOnly} 프로필에서만 등록되므로 prod에는 이 설정도, 아래 공개 경로 선언도 존재하지 않습니다.
 *
 * <p>공용 {@code SecurityConfig}의 {@code PUBLIC_PATHS}를 건드리지 않고 dev 경로의 공개 노출을 프로필 게이트와 같은 곳에 둡니다.
 * 이렇게 하면 prod 보안 설정이 dev 전용 경로를 알 필요가 없습니다(Secure by Default).
 */
@DevOnly
@Configuration
@EnableConfigurationProperties(DevTokenProperties.class)
class DevTokenConfig {

  /**
   * dev 토큰 엔드포인트 전용 공개 체인. {@code /api/dev/auth/token}만 매칭하고 자원서버를 얹지 않은 채 permitAll로 둡니다. 호출자 제한은
   * 컨트롤러가 공유 시크릿 헤더로 처리합니다. 가장 좁은 매처라 공개 체인(@Order(1))·보호 체인(@Order(2))보다 앞에 둡니다.
   */
  @Bean
  @Order(0)
  SecurityFilterChain devTokenSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/dev/auth/token")
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
