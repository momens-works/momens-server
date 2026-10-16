package works.momens.server.project.milestone.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;

@Service
@RequiredArgsConstructor
class MilestoneReaderImpl implements MilestoneReader {

  private final MilestoneRepository milestoneRepository;
  private final MilestoneOwnerRepository milestoneOwnerRepository;

  @Override
  @Transactional(readOnly = true)
  public List<MilestoneDetail> listDetailsByWorkspaceId(UUID workspaceId) {
    return withOwners(milestoneRepository.findDetailRowsByWorkspaceId(workspaceId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<MilestoneDetail> listPage(
      UUID workspaceId, UUID projectId, Instant before, UUID beforeId, int limit) {
    return withOwners(
        milestoneRepository.findPage(
            workspaceId, projectId, before, beforeId, PageRequest.of(0, limit)));
  }

  /** 소유자를 한 번의 배치 조회로 채웁니다. 소유자 행이 없으면 폴백할 컬럼이 없으므로 빈 목록입니다. */
  private List<MilestoneDetail> withOwners(List<MilestoneDetailRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> milestoneIds = rows.stream().map(MilestoneDetailRow::id).toList();
    Map<UUID, List<UUID>> ownersByMilestoneId =
        milestoneOwnerRepository
            .findByMilestoneIdInOrderByCreatedAtAscOwnerUserIdAsc(milestoneIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    MilestoneOwner::getMilestoneId,
                    // groupingBy는 스트림 순서를 유지하므로 정렬은 조회에서 정해진 그대로다.
                    Collectors.mapping(MilestoneOwner::getOwnerUserId, Collectors.toList())));
    return rows.stream()
        .map(row -> row.toDetail(ownersByMilestoneId.getOrDefault(row.id(), List.of())))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<MilestoneDetail> listByIds(UUID workspaceId, Collection<UUID> ids) {
    return ids.isEmpty()
        ? List.of()
        : withOwners(milestoneRepository.findRowsByIds(workspaceId, ids));
  }
}
