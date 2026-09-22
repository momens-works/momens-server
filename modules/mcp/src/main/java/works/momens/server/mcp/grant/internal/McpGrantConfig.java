package works.momens.server.mcp.grant.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MCP grant application service wiring. */
@Configuration
class McpGrantConfig {

  @Bean
  McpGrantService mcpGrantService(
      McpGrantRepository mcpGrantRepository,
      works.momens.server.workspace.membership.WorkspaceMembershipReader workspaceMembershipReader,
      works.momens.server.mcp.grant.McpTokenFamilyRevoker tokenFamilyRevoker,
      Clock clock) {
    return new McpGrantService(
        mcpGrantRepository, workspaceMembershipReader, tokenFamilyRevoker, clock);
  }
}
