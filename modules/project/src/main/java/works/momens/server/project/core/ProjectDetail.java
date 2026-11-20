package works.momens.server.project.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 웹 조회 모델은 {@link ProjectSnapshot}과 별개입니다. 저장된 진행률은 사용하지 않으며 task 상태로 계산합니다(ADR-0013).
 *
 * @param ownerUserIds 생성 시각·사용자 id 순서이며, 소유자 행이 없으면 {@code ownerId} 하나로 폴백합니다.
 * @param metadata 값이 없으면 null입니다.
 */
public record ProjectDetail(
    UUID id,
    UUID workspaceId,
    String label,
    String name,
    String description,
    String status,
    UUID ownerId,
    List<UUID> ownerUserIds,
    LocalDate targetDate,
    String healthStatus,
    String summary,
    int unresolvedCount,
    int vocSignalCount,
    Instant lastContextAt,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {}
