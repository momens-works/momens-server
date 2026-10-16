package works.momens.server.project.core.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;

@Service
@RequiredArgsConstructor
class ProjectDetailReaderImpl implements ProjectDetailReader {

  private final ProjectRepository projectRepository;
  private final ProjectOwnerRepository projectOwnerRepository;

  @Override
  @Transactional(readOnly = true)
  public List<ProjectDetail> listDetailsByWorkspaceId(UUID workspaceId) {
    return withOwners(projectRepository.findDetailRowsByWorkspaceId(workspaceId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProjectDetail> listPage(UUID workspaceId, Instant before, UUID beforeId, int limit) {
    return withOwners(
        projectRepository.findPage(workspaceId, before, beforeId, PageRequest.of(0, limit)));
  }

  /**
   * 행 목록에 소유자를 채웁니다. {@code project_owners} 조회는 project 수와 무관하게 한 번입니다.
   *
   * <p>소유자 행이 없으면 {@code owner_id} 하나만 담습니다. 레거시가 집계 결과에 {@code COALESCE(..., ARRAY[p.owner_id])}를
   * 씌우는 동작과 같고, 그래서 {@code owner_user_ids}는 절대 비지 않습니다.
   */
  private List<ProjectDetail> withOwners(List<ProjectDetailRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> projectIds = rows.stream().map(ProjectDetailRow::id).toList();
    Map<UUID, List<UUID>> ownersByProjectId =
        projectOwnerRepository
            .findByProjectIdInOrderByCreatedAtAscOwnerUserIdAsc(projectIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    ProjectOwner::getProjectId,
                    // groupingBy는 스트림 순서를 유지하므로 정렬은 조회에서 정해진 그대로다.
                    Collectors.mapping(ProjectOwner::getOwnerUserId, Collectors.toList())));
    return rows.stream()
        .map(row -> row.toDetail(ownersByProjectId.getOrDefault(row.id(), List.of(row.ownerId()))))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProjectDetail> listByIds(UUID workspaceId, Collection<UUID> ids) {
    return ids.isEmpty()
        ? List.of()
        : withOwners(projectRepository.findRowsByIds(workspaceId, ids));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProjectDetail> findReferenceCandidates(
      UUID workspaceId, UUID referenceId, String reference) {
    if (referenceId != null) {
      return listByIds(workspaceId, List.of(referenceId));
    }
    // DB의 trim/lower는 Java의 Unicode 공백·대소문자 비교와 다르므로 제한 전에 Java에서 판정합니다.
    List<UUID> exact = new ArrayList<>();
    List<UUID> partial = new ArrayList<>();
    String lower = reference.toLowerCase(Locale.ROOT);
    UUID afterId = null;
    while (true) {
      List<ProjectReferenceRow> batch =
          projectRepository.findReferencePage(workspaceId, afterId, PageRequest.of(0, 100));
      for (ProjectReferenceRow row : batch) {
        if (reference.equalsIgnoreCase(row.label())) {
          return listByIds(workspaceId, List.of(row.id()));
        }
        String name = row.name().strip();
        if (name.equalsIgnoreCase(reference)) {
          if (exact.size() < 2) {
            exact.add(row.id());
          }
        } else if (partial.size() < 2 && name.toLowerCase(Locale.ROOT).contains(lower)) {
          partial.add(row.id());
        }
      }
      if (batch.size() < 100) {
        break;
      }
      ProjectReferenceRow last = batch.getLast();
      afterId = last.id();
    }
    return listByIds(workspaceId, exact.isEmpty() ? partial : exact);
  }
}
