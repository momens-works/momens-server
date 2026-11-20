package works.momens.server.mcp.oauth.infrastructure;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import works.momens.server.mcp.McpClientReader;

@Component
@RequiredArgsConstructor
class McpClientReaderAdapter implements McpClientReader {
  private final RegisteredClientRepository clients;

  @Override
  public Optional<String> findClientName(String clientId) {
    return Optional.ofNullable(clients.findByClientId(clientId))
        .map(RegisteredClient::getClientName);
  }
}
