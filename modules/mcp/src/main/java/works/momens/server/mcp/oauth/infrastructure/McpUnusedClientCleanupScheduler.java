package works.momens.server.mcp.oauth.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "momens.mcp.oauth.unused-client-cleanup.enabled",
    havingValue = "true")
class McpUnusedClientCleanupScheduler {
  private final McpUnusedClientCleanupService cleanup;

  @Scheduled(initialDelayString = "1m", fixedDelayString = "1m")
  void deleteUnusedClients() {
    try {
      int deleted = cleanup.deleteUnusedClients();
      if (deleted > 0) {
        log.info("Unused MCP client cleanup deleted={}", deleted);
      }
    } catch (RuntimeException exception) {
      log.error("Unused MCP client cleanup failed", exception);
    }
  }
}
