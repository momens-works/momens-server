package works.momens.server.project.milestone.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.milestone.MilestoneDetail;

/** 마일스톤 행을 조회한 뒤 소유자를 별도 배치 조회해 결합합니다. 행별 추가 조회는 하지 않습니다. */
record MilestoneDetailRow(
    UUID id,
    UUID projectId,
    String name,
    String description,
    LocalDate targetDate,
    String status,
    String healthStatus,
    int progress,
    String summary,
    Instant lastContextAt,
    Instant createdAt,
    Instant updatedAt) {

  MilestoneDetail toDetail(List<UUID> ownerUserIds) {
    return new MilestoneDetail(
        id,
        projectId,
        name,
        description,
        targetDate,
        status,
        ownerUserIds,
        healthStatus,
        progress,
        summary,
        lastContextAt,
        createdAt,
        updatedAt);
  }
}
