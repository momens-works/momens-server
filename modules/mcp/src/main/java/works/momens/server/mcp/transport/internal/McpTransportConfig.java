package works.momens.server.mcp.transport.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class McpTransportConfig {

  @Bean
  @Order(0)
  SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            "/api/mcp", "/api/mcp/**", "/.well-known/oauth-protected-resource/mcp")
        // MCP transport is stateless and authenticates requests with Bearer tokens, not cookies.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/mcp", "/api/mcp/**"))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
