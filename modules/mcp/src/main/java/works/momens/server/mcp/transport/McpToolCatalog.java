package works.momens.server.mcp.transport;

import java.util.List;

/** 인증 문맥에 맞는 deterministic tools/list 결과를 제공하는 port입니다. */
public interface McpToolCatalog {
  List<McpToolDefinition> list(McpAuthenticationContext authenticationContext);
}
