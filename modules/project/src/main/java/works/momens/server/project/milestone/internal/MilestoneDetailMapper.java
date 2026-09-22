package works.momens.server.project.milestone.internal;

import java.util.List;
import java.util.UUID;
import works.momens.server.project.milestone.MilestoneDetail;

final class MilestoneDetailMapper {

  private MilestoneDetailMapper() {}

  static MilestoneDetail toDetail(Milestone milestone, List<UUID> ownerUserIds) {
    return new MilestoneDetail(
        milestone.getId(),
        milestone.getProjectId(),
        milestone.getName(),
        milestone.getDescription(),
        milestone.getTargetDate(),
        milestone.getStatus(),
        ownerUserIds,
        milestone.getHealthStatus(),
        milestone.getProgress(),
        milestone.getSummary(),
        milestone.getLastContextAt(),
        milestone.getCreatedAt(),
        milestone.getUpdatedAt());
  }
}
