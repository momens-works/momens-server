package works.momens.server.memory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 후보 검토 없이 초기 데이터를 만드는 입력입니다. {@code metadata}가 없으면 SQL NULL로 저장합니다. */
public record CreateConfirmedMemoryCommand(
    UUID workspaceId,
    UUID confirmedByUserId,
    String memoryType,
    String title,
    String body,
    List<UUID> relatedEntityIds,
    Map<String, Object> metadata) {}
