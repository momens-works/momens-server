package works.momens.server.mcp;

import java.util.Optional;

/** Public client display information; credentials and protocol state are not exposed. */
public interface McpClientReader {
  Optional<String> findClientName(String clientId);
}
