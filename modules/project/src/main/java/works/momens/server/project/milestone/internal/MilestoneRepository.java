package works.momens.server.project.milestone.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

  @Lock(LockModeType.OPTIMISTIC)
  @Query("select e from Milestone e where e.id = :id and e.deletedAt is null")
  Optional<Milestone> findForMutation(UUID id);

  Optional<Milestone> findByIdAndDeletedAtIsNull(UUID id);

  boolean existsByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);

  /**
   * 호출자가 넘긴 project id에 한정합니다. 소유자는 별도 배치 조회로 결합합니다.
   *
   * <p>생성 시각 내림차순이며 레거시와 같이 동률 보조 키는 두지 않습니다.
   */
  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt, m.version)
      from Milestone m
      where m.projectId in :projectIds
        and m.deletedAt is null
      order by m.createdAt desc
      """)
  List<MilestoneDetailRow> findDetailRowsByProjectIds(
      @Param("projectIds") Collection<UUID> projectIds);

  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt, m.version)
      from Milestone m
      where m.projectId in :projectIds
        and m.deletedAt is null
      and (:beforeId is null or m.createdAt < :before or (m.createdAt = :before and m.id < :beforeId))
      order by m.createdAt desc, m.id desc
      """)
  List<MilestoneDetailRow> findPage(
      Collection<UUID> projectIds, Instant before, UUID beforeId, Pageable pageable);

  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt, m.version)
      from Milestone m
      where m.projectId in :projectIds
        and m.deletedAt is null
      and m.id in :ids
      order by m.createdAt desc
      """)
  List<MilestoneDetailRow> findRowsByIds(Collection<UUID> projectIds, Collection<UUID> ids);
}
