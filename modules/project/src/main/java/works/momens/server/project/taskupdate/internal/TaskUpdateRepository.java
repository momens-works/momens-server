package works.momens.server.project.taskupdate.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TaskUpdateRepository extends JpaRepository<TaskUpdate, UUID> {

  List<TaskUpdate> findByTaskIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID taskId);

  Optional<TaskUpdate> findByIdAndTaskIdAndDeletedAtIsNull(UUID id, UUID taskId);

  @Query(
      """
      select t from TaskUpdate t
      where t.taskId = :taskId and t.deletedAt is null

        and (:beforeId is null or t.createdAt < :before or (t.createdAt = :before and t.id < :beforeId))
      order by t.createdAt desc, t.id desc
      """)
  List<TaskUpdate> findPage(UUID taskId, Instant before, UUID beforeId, Pageable pageable);

  long countByTaskIdAndDeletedAtIsNull(UUID taskId);
}
