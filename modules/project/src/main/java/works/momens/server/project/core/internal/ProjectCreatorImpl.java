package works.momens.server.project.core.internal;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.project.core.CreateProjectCommand;
import works.momens.server.project.core.ProjectCreator;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.workspace.label.LabelAllocator;

/**
 * 레거시 프로젝트 생성 서비스의 검증과 저장 동작을 이관한 구현입니다.
 *
 * <p>검증 순서는 레거시와 동일하게 프로젝트 이름, 소유자, {@code health_status}, 진행률, 미해결 항목 수, VOC 수 순입니다. 레거시는 프로젝트 이름을
 * 요청 본문 역직렬화 단계에서 검증하고 나머지 값은 서비스에서 검증합니다. 여러 필드가 동시에 유효하지 않은 요청에서는 이 순서에 따라 응답에 포함할 오류가 결정됩니다.
 *
 * <p>날짜 형식이 올바르지 않은 요청은 이 구현까지 전달되지 않으며 요청 본문 역직렬화 단계에서 거부됩니다.
 *
 * <p>프로젝트 라벨은 {@link works.momens.server.workspace.label.LabelAllocator}를 통해 발급합니다. 레거시는 라벨 없이
 * INSERT하면 트리거가 값을 채우지만, 신규 서버의 local 스키마에는 해당 트리거가 없습니다. 따라서 라벨을 항상 직접 발급해 INSERT 문에 포함합니다.
 */
@Service
@RequiredArgsConstructor
class ProjectCreatorImpl implements ProjectCreator {

  private static final String FIELD_NAME = "name";
  private static final String FIELD_HEALTH_STATUS = "health_status";
  private static final String FIELD_PROGRESS = "progress";
  private static final String FIELD_UNRESOLVED_COUNT = "unresolved_count";
  private static final String FIELD_VOC_SIGNAL_COUNT = "voc_signal_count";

  private final ProjectRepository projectRepository;
  private final ProjectOwnerRepository projectOwnerRepository;
  private final ProjectOwnerMembershipChecker ownerMembershipChecker;
  private final LabelAllocator labelAllocator;

  @Override
  @Transactional
  public ProjectDetail create(CreateProjectCommand command) {
    if (command.name() == null || command.name().isEmpty()) {
      throw FieldValidationException.forField(FIELD_NAME);
    }
    List<UUID> ownerUserIds = resolveOwnerUserIds(command);
    ownerMembershipChecker.requireWorkspaceMembers(command.workspaceId(), ownerUserIds);

    // 소유자 행이 projects를 참조하므로 프로젝트 INSERT를 먼저 실행합니다.
    Project project =
        projectRepository.saveAndFlush(
            Project.builder()
                .workspaceId(command.workspaceId())
                .name(command.name())
                .description(emptyToNull(command.description()))
                .ownerId(ownerUserIds.getFirst())
                .targetDate(command.targetDate())
                .summary(emptyToNull(command.summary()))
                .label(labelAllocator.allocateProjectLabel(command.workspaceId()))
                .healthStatus(healthStatusOf(command.healthStatus()))
                .progress(progressOf(command.progress()))
                .unresolvedCount(countOf(command.unresolvedCount(), FIELD_UNRESOLVED_COUNT))
                .vocSignalCount(countOf(command.vocSignalCount(), FIELD_VOC_SIGNAL_COUNT))
                .lastContextAt(command.lastContextAt())
                .metadata(command.metadata())
                .build());
    projectOwnerRepository.saveAll(
        ownerUserIds.stream().map(ownerId -> ProjectOwner.of(project.getId(), ownerId)).toList());

    return toDetail(project, ownerUserIds);
  }

  private static List<UUID> resolveOwnerUserIds(CreateProjectCommand command) {
    List<UUID> requested = command.ownerUserIds();
    return requested == null || requested.isEmpty()
        ? List.of(command.requesterId())
        : List.copyOf(requested);
  }

  private static String healthStatusOf(String requested) {
    if (requested == null || requested.isEmpty()) {
      return HealthStatus.OPEN.value();
    }
    return HealthStatus.from(requested)
        .orElseThrow(() -> FieldValidationException.forField(FIELD_HEALTH_STATUS))
        .value();
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

  private static int countOf(Integer requested, String field) {
    if (requested == null) {
      return 0;
    }
    if (requested < 0) {
      throw FieldValidationException.forField(field);
    }
    return requested;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static ProjectDetail toDetail(Project project, List<UUID> ownerUserIds) {
    return new ProjectDetail(
        project.getId(),
        project.getWorkspaceId(),
        project.getLabel(),
        project.getName(),
        project.getDescription(),
        project.getStatus(),
        project.getOwnerId(),
        ownerUserIds,
        project.getTargetDate(),
        project.getHealthStatus(),
        project.getSummary(),
        project.getUnresolvedCount(),
        project.getVocSignalCount(),
        project.getLastContextAt(),
        project.getMetadata(),
        project.getCreatedAt(),
        project.getUpdatedAt());
  }
}
