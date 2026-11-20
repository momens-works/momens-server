package works.momens.server.project.blocker.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import works.momens.server.project.blocker.BlockerDetail;

interface BlockerRepository extends JpaRepository<Blocker, UUID> {

  @Query("select b.workspaceId from Blocker b where b.id = :id")
  Optional<UUID> findWorkspaceIdById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Blocker b where b.id = :id and b.workspaceId = :workspaceId")
  Optional<Blocker> findForUpdate(@Param("workspaceId") UUID workspaceId, @Param("id") UUID id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update Blocker b set b.status = :status, b.resolvedAt = :resolvedAt,
          b.updatedAt = :resolvedAt
      where b.id = :id and b.workspaceId = :workspaceId
      """)
  void resolve(
      @Param("workspaceId") UUID workspaceId,
      @Param("id") UUID id,
      @Param("status") String status,
      @Param("resolvedAt") Instant resolvedAt);

  @Query(
      """
      select new works.momens.server.project.blocker.BlockerDetail(
          b.id, b.workspaceId, b.description, b.status, b.blockedEntityType,
          b.blockedEntityId, b.createdAt, b.updatedAt, b.resolvedAt)
      from Blocker b
      where b.workspaceId = :workspaceId
      order by b.createdAt desc
      """)
  List<BlockerDetail> findDetailsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
