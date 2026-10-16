package works.momens.server.project.core.internal;

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

interface ProjectRepository extends JpaRepository<Project, UUID> {

  Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select p from Project p where p.id = :id and p.deletedAt is null")
  Optional<Project> findActiveWithLock(@Param("id") UUID id);

  List<Project> findByWorkspaceIdInAndDeletedAtIsNullOrderByCreatedAtDesc(
      Collection<UUID> workspaceIds);

  /** 소유자는 별도 배치 조회로 조합해 행별 추가 조회를 피합니다. 프로젝트는 생성 시각 내림차순이며 레거시와 같이 동률 보조 키를 두지 않습니다. */
  @Query(
      """
      select new works.momens.server.project.core.internal.ProjectDetailRow(
          p.id, p.workspaceId, p.label, p.name, p.description, p.status, p.ownerId,
          p.targetDate, p.healthStatus, p.summary, p.unresolvedCount, p.vocSignalCount,
          p.lastContextAt, p.metadata, p.createdAt, p.updatedAt)
      from Project p
      where p.workspaceId = :workspaceId and p.deletedAt is null
      order by p.createdAt desc
      """)
  List<ProjectDetailRow> findDetailRowsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

  @Query(
      """
      select new works.momens.server.project.core.internal.ProjectDetailRow(
          p.id, p.workspaceId, p.label, p.name, p.description, p.status, p.ownerId,
          p.targetDate, p.healthStatus, p.summary, p.unresolvedCount, p.vocSignalCount,
          p.lastContextAt, p.metadata, p.createdAt, p.updatedAt)
      from Project p
      where p.workspaceId = :workspaceId and p.deletedAt is null
      and (:beforeId is null or p.createdAt < :before or (p.createdAt = :before and p.id < :beforeId))
      order by p.createdAt desc, p.id desc
      """)
  List<ProjectDetailRow> findPage(
      UUID workspaceId, Instant before, UUID beforeId, Pageable pageable);

  @Query(
      """
      select new works.momens.server.project.core.internal.ProjectDetailRow(
          p.id, p.workspaceId, p.label, p.name, p.description, p.status, p.ownerId,
          p.targetDate, p.healthStatus, p.summary, p.unresolvedCount, p.vocSignalCount,
          p.lastContextAt, p.metadata, p.createdAt, p.updatedAt)
      from Project p
      where p.workspaceId = :workspaceId and p.deletedAt is null
      and p.id in :ids
      order by p.createdAt desc
      """)
  List<ProjectDetailRow> findRowsByIds(UUID workspaceId, Collection<UUID> ids);

  @Query(
      """
      select new works.momens.server.project.core.internal.ProjectDetailRow(
          p.id, p.workspaceId, p.label, p.name, p.description, p.status, p.ownerId,
          p.targetDate, p.healthStatus, p.summary, p.unresolvedCount, p.vocSignalCount,
          p.lastContextAt, p.metadata, p.createdAt, p.updatedAt)
      from Project p
      where p.workspaceId = :workspaceId and p.deletedAt is null
      and ((:referenceId is not null and p.id = :referenceId) or
        (:referenceId is null and (lower(p.label) = lower(:reference) or locate(lower(:reference), lower(trim(p.name))) > 0)))
      order by case when p.id = :referenceId or lower(p.label) = lower(:reference) then 0
        when lower(trim(p.name)) = lower(:reference) then 1 else 2 end, p.id
      """)
  List<ProjectDetailRow> findReferenceCandidates(
      UUID workspaceId, UUID referenceId, String reference, Pageable pageable);
}
