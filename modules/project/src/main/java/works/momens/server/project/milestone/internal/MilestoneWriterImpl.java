package works.momens.server.project.milestone.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.project.core.ProjectOwnerReader;
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneHealthStatus;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;

@Service
@RequiredArgsConstructor
class MilestoneWriterImpl implements MilestoneWriter {

  private static final String FIELD_HEALTH_STATUS = "health_status";
  private static final String FIELD_PROGRESS = "progress";
  private static final String FIELD_STATUS = "status";

  private final MilestoneRepository milestoneRepository;
  private final MilestoneOwnerRepository milestoneOwnerRepository;
  private final ProjectOwnerReader projectOwnerReader;
  private final MilestoneOwnerMembershipChecker ownerMembershipChecker;

  @Override
  @Transactional
  public MilestoneDetail create(CreateMilestoneCommand command) {
    if (command.name() == null || command.name().isEmpty()) {
      throw FieldValidationException.forField("name");
    }
    List<UUID> ownerUserIds = resolveOwnerUserIds(command);
    ownerMembershipChecker.requireWorkspaceMembers(command.workspaceId(), ownerUserIds);

    Milestone milestone =
        milestoneRepository.saveAndFlush(
            Milestone.builder()
                .projectId(command.projectId())
                .name(command.name())
                .description(emptyToNull(command.description()))
                .targetDate(command.targetDate())
                .healthStatus(healthStatusOf(command.healthStatus()))
                .progress(progressOf(command.progress()))
                .summary(emptyToNull(command.summary()))
                .lastContextAt(command.lastContextAt())
                .build());
    milestoneOwnerRepository.saveAll(
        ownerUserIds.stream()
            .map(ownerId -> MilestoneOwner.of(milestone.getId(), ownerId))
            .toList());

    return MilestoneDetailMapper.toDetail(milestone, ownerUserIds);
  }

  @Override
  @Transactional
  public MilestoneDetail update(UpdateMilestoneCommand command) {
    Milestone milestone = findMilestone(command.milestoneId());
    if (command.healthStatus() != null && !command.healthStatus().isEmpty()) {
      MilestoneHealthStatus.from(command.healthStatus())
          .orElseThrow(() -> FieldValidationException.forField(FIELD_HEALTH_STATUS));
    }
    if (command.status() != null && !command.status().isEmpty()) {
      MilestoneStatus.from(command.status())
          .orElseThrow(() -> FieldValidationException.forField(FIELD_STATUS));
    }
    if (command.progress() != null && (command.progress() < 0 || command.progress() > 100)) {
      throw FieldValidationException.forField(FIELD_PROGRESS);
    }

    milestone.update(
        command.name(),
        command.description(),
        command.status(),
        command.targetDate(),
        command.healthStatus(),
        command.progress(),
        command.summary());
    milestoneRepository.saveAndFlush(milestone);
    return MilestoneDetailMapper.toDetail(milestone, ownerUserIds(milestone.getId()));
  }

  @Override
  @Transactional
  public void delete(UUID milestoneId) {
    findMilestone(milestoneId).delete();
  }

  private Milestone findMilestone(UUID milestoneId) {
    return milestoneRepository
        .findByIdAndDeletedAtIsNull(milestoneId)
        .orElseThrow(
            () ->
                new BusinessException(
                    CommonErrorCode.COMMON_NOT_FOUND,
                    Map.of("milestone_id", milestoneId.toString())));
  }

  private List<UUID> ownerUserIds(UUID milestoneId) {
    return milestoneOwnerRepository
        .findByMilestoneIdInOrderByCreatedAtAscOwnerUserIdAsc(List.of(milestoneId))
        .stream()
        .map(MilestoneOwner::getOwnerUserId)
        .toList();
  }

  private List<UUID> resolveOwnerUserIds(CreateMilestoneCommand command) {
    List<UUID> requested = command.ownerUserIds();
    if (requested != null && !requested.isEmpty()) {
      return List.copyOf(requested);
    }
    List<UUID> projectOwnerUserIds = projectOwnerReader.listOwnerUserIds(command.projectId());
    return projectOwnerUserIds.isEmpty() ? List.of(command.requesterId()) : projectOwnerUserIds;
  }

  private static String healthStatusOf(MilestoneHealthStatus requested) {
    return (requested == null ? MilestoneHealthStatus.PLANNED : requested).value();
  }

  private static int progressOf(Integer requested) {
    if (requested == null) {
      return 0;
    }
    if (requested < 0 || requested > 100) {
      throw FieldValidationException.forField(FIELD_PROGRESS);
    }
    return requested;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
