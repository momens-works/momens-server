package works.momens.server.project.milestone.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

  Optional<Milestone> findByIdAndDeletedAtIsNull(UUID id);

  boolean existsByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);

  /**
   * 워크스페이스 범위를 제한하고 프로젝트의 생존 여부를 확인하기 위해 projects를 조인합니다. 소유자는 별도 배치 조회로 결합합니다.
   *
   * <p>생성 시각 내림차순이며 레거시와 같이 동률 보조 키는 두지 않습니다.
   */
  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt)
      from Milestone m
      join Project p on p.id = m.projectId
      where p.workspaceId = :workspaceId
        and m.deletedAt is null
        and p.deletedAt is null
      order by m.createdAt desc
      """)
  List<MilestoneDetailRow> findDetailRowsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt)
      from Milestone m
      join Project p on p.id = m.projectId
      where p.workspaceId = :workspaceId
        and m.deletedAt is null
        and p.deletedAt is null
      and (:beforeId is null or m.createdAt < :before or (m.createdAt = :before and m.id < :beforeId))
      and (:projectId is null or m.projectId = :projectId)
      order by m.createdAt desc, m.id desc
      """)
  List<MilestoneDetailRow> findPage(
      UUID workspaceId, UUID projectId, Instant before, UUID beforeId, Pageable pageable);

  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt)
      from Milestone m
      join Project p on p.id = m.projectId
      where p.workspaceId = :workspaceId
        and m.deletedAt is null
        and p.deletedAt is null
      and m.id in :ids
      order by m.createdAt desc
      """)
  List<MilestoneDetailRow> findRowsByIds(UUID workspaceId, Collection<UUID> ids);
}
