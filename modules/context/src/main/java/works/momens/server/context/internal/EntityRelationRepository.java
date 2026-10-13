package works.momens.server.context.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EntityRelationRepository extends JpaRepository<EntityRelation, UUID> {

  @Query(
      """
      select r.fromEntityId, r.toEntityId from EntityRelation r
      where r.workspaceId = :workspaceId
        and r.fromEntityType = 'TASK'
        and r.fromEntityId in :taskIds
        and r.relationType = 'LINKED_TO'
        and r.toEntityType = 'SOURCE_OBJECT'
        and r.deletedAt is null
      order by r.createdAt desc, r.id desc
      """)
  List<Object[]> findLinkedSourceRefIdsByTaskIds(
      @Param("workspaceId") UUID workspaceId, @Param("taskIds") Collection<UUID> taskIds);

  @Query(
      """
      select r.fromEntityId, r.toEntityType, r.toEntityId from EntityRelation r
      where r.workspaceId = :workspaceId and r.fromEntityType = 'TASK' and r.fromEntityId in :taskIds
        and r.relationType = 'LINKED_TO' and r.toEntityType in ('MEMORY', 'SOURCE_OBJECT')
        and r.deletedAt is null
      """)
  List<Object[]> findContextLinks(
      @Param("workspaceId") UUID workspaceId, @Param("taskIds") Collection<UUID> taskIds);
}
