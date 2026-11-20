package works.momens.server.mcp.oauth.infrastructure;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class McpUnusedClientCleanupService {
  private static final Duration RETENTION = Duration.ofHours(24);
  private static final int BATCH_SIZE = 100;

  private final McpUnusedClientRepository clients;
  private final Clock clock;

  @Transactional(timeout = 10)
  public int deleteUnusedClients() {
    int deleted = 0;
    for (String id : clients.lockUnusedBefore(clock.instant().minus(RETENTION), BATCH_SIZE)) {
      deleted += clients.deleteIfUnused(id);
    }
    return deleted;
  }
}
