package works.momens.server.mcp.grant.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/** MCP grant application service wiring. */
@Configuration
class McpGrantConfig {

  @Bean
  McpGrantService mcpGrantService(
      McpGrantRepository mcpGrantRepository,
      WorkspaceMembershipReader workspaceMembershipReader,
      McpTokenFamilyRevoker tokenFamilyRevoker,
      Clock clock) {
    return new McpGrantService(
        mcpGrantRepository, workspaceMembershipReader, tokenFamilyRevoker, clock);
  }
}
