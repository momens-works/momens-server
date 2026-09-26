package works.momens.server.mcp.transport.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import works.momens.server.mcp.configuration.McpEndpointProperties;

@Configuration
class McpTransportConfig {

  @Bean
  @Order(0)
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http, McpEndpointProperties endpointProperties) throws Exception {
    return http.securityMatcher(
            "/api/mcp",
            "/api/mcp/**",
            "/.well-known/oauth-protected-resource/api/mcp",
            "/.well-known/oauth-authorization-server/api")
        // MCP transport is stateless and authenticates requests with Bearer tokens, not cookies.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/mcp", "/api/mcp/**"))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(
            new McpOriginValidationFilter(endpointProperties), AuthorizationFilter.class)
        .build();
  }
}
