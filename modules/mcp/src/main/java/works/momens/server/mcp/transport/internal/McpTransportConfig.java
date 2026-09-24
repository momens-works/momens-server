package works.momens.server.mcp.transport.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.mcp.transport.McpToolCatalog;

@Configuration
class McpTransportConfig {

  @Bean
  @Order(0)
  SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/mcp", "/api/mcp/**")
        // MCP transport is stateless and authenticates requests with Bearer tokens, not cookies.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/mcp", "/api/mcp/**"))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  McpBearerTokenVerifier mcpBearerTokenVerifier() {
    return token -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean
  McpToolCatalog mcpToolCatalog() {
    return authenticationContext -> List.of();
  }
}
