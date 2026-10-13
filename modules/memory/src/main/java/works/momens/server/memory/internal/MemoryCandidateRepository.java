package works.momens.server.memory.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import works.momens.server.memory.MemoryCandidateDetail;

interface MemoryCandidateRepository extends JpaRepository<MemoryCandidate, UUID> {

  /** 후보 상태로 거르지 않으며 중요도가 없는 행은 뒤로 보냅니다. 같은 중요도에서는 생성 시각 내림차순입니다. */
  @Query(
      """
      select new works.momens.server.memory.MemoryCandidateDetail(
          c.id, c.workspaceId, c.label, c.candidateType, c.title, c.summary, c.body,
          c.confidence, c.importance, c.status, c.sourceRefIds, c.relatedEntityIds,
          c.proposedBy, c.reviewedAt, c.reviewedByUserId, c.rejectionReason, c.expiresAt,
          c.metadata, c.createdAt, c.updatedAt)
      from MemoryCandidate c
      where c.workspaceId = :workspaceId
      order by c.importance desc nulls last, c.createdAt desc
      """)
  List<MemoryCandidateDetail> findDetailsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
