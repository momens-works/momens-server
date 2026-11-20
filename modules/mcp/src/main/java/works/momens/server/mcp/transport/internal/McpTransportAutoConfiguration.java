package works.momens.server.mcp.transport.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import works.momens.server.mcp.configuration.McpEndpointProperties;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.mcp.transport.McpToolCatalog;

@AutoConfiguration
@EnableConfigurationProperties(McpEndpointProperties.class)
public class McpTransportAutoConfiguration {

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
