package works.momens.server.context;

import java.util.List;
import java.util.UUID;

public record TaskContextLinks(List<UUID> memoryIds, List<UUID> sourceRefIds) {
  public TaskContextLinks {
    memoryIds = memoryIds == null ? List.of() : memoryIds;
    sourceRefIds = sourceRefIds == null ? List.of() : sourceRefIds;
  }
}
