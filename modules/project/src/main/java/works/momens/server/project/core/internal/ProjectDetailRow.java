package works.momens.server.project.core.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.project.core.ProjectDetail;

/** 프로젝트 행을 조회한 뒤 소유자를 별도 배치 조회해 결합합니다. 행별 추가 조회는 하지 않습니다. */
record ProjectDetailRow(
    UUID id,
    UUID workspaceId,
    String label,
    String name,
    String description,
    String status,
    UUID ownerId,
    LocalDate targetDate,
    String healthStatus,
    String summary,
    int unresolvedCount,
    int vocSignalCount,
    Instant lastContextAt,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {

  ProjectDetail toDetail(List<UUID> ownerUserIds) {
    return new ProjectDetail(
        id,
        workspaceId,
        label,
        name,
        description,
        status,
        ownerId,
        ownerUserIds,
        targetDate,
        healthStatus,
        summary,
        unresolvedCount,
        vocSignalCount,
        lastContextAt,
        metadata,
        createdAt,
        updatedAt);
  }
}
