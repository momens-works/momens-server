package works.momens.server.memory.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import works.momens.server.memory.ConfirmedMemoryDetail;

interface ConfirmedMemoryRepository extends JpaRepository<ConfirmedMemory, UUID> {

  /** 상태와 무관하게 {@code deleted_at}이 없는 행을 조회합니다. 레거시와 같이 생성 시각 내림차순이며 동률 보조 키는 두지 않습니다. */
  @Query(
      """
      select new works.momens.server.memory.ConfirmedMemoryDetail(
          m.id, m.workspaceId, m.label, m.memoryType, m.title, m.summary, m.body, m.status,
          m.sourceRefIds, m.relatedEntityIds, m.createdFromCandidateId, m.confirmedByUserId,
          m.confirmedAt, m.validFrom, m.validUntil, m.invalidatedAt, m.invalidatedByUserId,
          m.invalidationReason, m.metadata, m.createdAt, m.updatedAt)
      from ConfirmedMemory m
      where m.workspaceId = :workspaceId
        and m.deletedAt is null
      order by m.createdAt desc
      """)
  List<ConfirmedMemoryDetail> findDetailsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

  @Query(
      """
      select new works.momens.server.memory.ConfirmedMemoryDetail(
          m.id, m.workspaceId, m.label, m.memoryType, m.title, m.summary, m.body, m.status,
          m.sourceRefIds, m.relatedEntityIds, m.createdFromCandidateId, m.confirmedByUserId,
          m.confirmedAt, m.validFrom, m.validUntil, m.invalidatedAt, m.invalidatedByUserId,
          m.invalidationReason, m.metadata, m.createdAt, m.updatedAt)
      from ConfirmedMemory m
      where m.workspaceId = :workspaceId and m.id in :ids and m.deletedAt is null
      order by m.createdAt desc
      """)
  List<ConfirmedMemoryDetail> findDetailsByWorkspaceIdAndIdIn(
      @Param("workspaceId") UUID workspaceId, @Param("ids") Collection<UUID> ids);

  @Query(
      """
      select new works.momens.server.memory.ConfirmedMemoryDetail(
          m.id, m.workspaceId, m.label, m.memoryType, m.title, m.summary, m.body, m.status,
          m.sourceRefIds, m.relatedEntityIds, m.createdFromCandidateId, m.confirmedByUserId,
          m.confirmedAt, m.validFrom, m.validUntil, m.invalidatedAt, m.invalidatedByUserId,
          m.invalidationReason, m.metadata, m.createdAt, m.updatedAt)
      from ConfirmedMemory m where m.id = :id and m.deletedAt is null
      """)
  Optional<ConfirmedMemoryDetail> findDetailById(@Param("id") UUID id);

  @Query("select m.workspaceId from ConfirmedMemory m where m.id = :id and m.deletedAt is null")
  Optional<UUID> findWorkspaceIdById(@Param("id") UUID id);
}
