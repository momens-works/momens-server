package works.momens.server.memory.internal;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.context.EntityRelationCommand;
import works.momens.server.context.EntityRelationWriter;
import works.momens.server.context.EntityType;
import works.momens.server.context.RelationType;
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.memory.MemoryWriter;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 레거시 {@code memory/service.go}의 후보 리뷰·메모리 해결 write 경로를 옮긴 구현입니다.
 *
 * <p>JPA 엔티티 대신 네이티브 SQL로 저장합니다. 값이 비어 있지 않은 컬럼만 덮어쓰는 부분 UPDATE, 후보 행의 값을 복사하는 INSERT, 행 잠금이 필요하기
 * 때문입니다. 레거시가 소유한 테이블이므로 레거시 동작에 필요한 컬럼만 명시적으로 변경합니다. 새 행 생성만 담당하는 {@link
 * ConfirmedMemoryCreatorImpl}은 엔티티를 사용합니다.
 *
 * <p>검사 순서는 레거시를 따릅니다. 워크스페이스 확인(404) → 멤버십(403) → 잠금 → 상태(409) 순이며, 순서가 바뀌면 비멤버가 후보 상태를 알게 되고 레거시와
 * status code가 갈립니다.
 *
 * <p>projection은 레거시가 {@code retrieval_documents}·{@code retrieval_events}에 직접 쓰던 것을 outbox 이벤트로
 * 대신합니다(ADR-0008). 검색 반영은 worker consumer가 담당하며 그 구현이 cutover gate입니다({@code MOM-0898}). 레거시가
 * projection을 남기지 않는 reject·merge·expire는 이 서버도 이벤트를 남기지 않습니다.
 */
@Service
@RequiredArgsConstructor
class MemoryWriterImpl implements MemoryWriter {

  private static final String STATUS_PROPOSED = "PROPOSED";
  private static final String ACTION_CONFIRM = "CONFIRM";
  private static final String ACTION_EDIT_AND_CONFIRM = "EDIT_AND_CONFIRM";
  private static final String ACTION_REJECT = "REJECT";
  private static final String ACTION_MERGE = "MERGE";
  private static final String ACTION_EXPIRE = "EXPIRE";
  private static final String AGGREGATE_MEMORY = "memory";
  private static final String EVENT_CONFIRMED = "memory.confirmed";
  private static final String EVENT_UPDATED = "memory.updated";

  private final EntityManager entityManager;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final LabelAllocator labelAllocator;
  private final EntityRelationWriter relationWriter;
  private final ConfirmedMemoryRepository memoryRepository;
  private final OutboxAppender outboxAppender;

  @Override
  @Transactional
  public ConfirmedMemoryDetail confirm(UUID candidateId, UUID userId) {
    return confirmInternal(candidateId, userId, null, ACTION_CONFIRM);
  }

  @Override
  @Transactional
  public ConfirmedMemoryDetail editAndConfirm(UUID candidateId, UUID userId, CandidateEdits edits) {
    return confirmInternal(candidateId, userId, edits, ACTION_EDIT_AND_CONFIRM);
  }

  private ConfirmedMemoryDetail confirmInternal(
      UUID candidateId, UUID userId, CandidateEdits edits, String actionType) {
    UUID workspaceId = requireCandidateMembership(candidateId, userId);
    lockProposedCandidate(candidateId);

    String label = labelAllocator.allocateMemoryLabel(workspaceId);
    UUID memoryId = UUID.randomUUID();
    Instant now = Instant.now();

    // 레거시는 비어 있지 않은 편집 필드만 후보 값을 덮어쓰고, 덮어쓴 값만 review action에 남깁니다.
    String editedTitle = edits == null ? null : emptyToNull(edits.title());
    String editedSummary = edits == null ? null : emptyToNull(edits.summary());
    String editedBody = edits == null ? null : emptyToNull(edits.body());

    markCandidateReviewed(candidateId, "CONFIRMED", userId, null, now);
    // metadata는 담지 않습니다. 레거시는 확정 메모리를 만들 때 후보의 metadata를 옮기지 않고 항상 NULL로
    // 둡니다(memory/repository.go InsertMemory가 세팅되지 않은 필드를 그대로 넣습니다). 후보 metadata는
    // 워커가 남긴 추출 정보라 확정 메모리로 새어 나가면 안 됩니다.
    entityManager
        .createNativeQuery(
            """
            INSERT INTO confirmed_memories (
                id, workspace_id, label, memory_type, title, summary, body, status,
                source_ref_ids, related_entity_ids, created_from_candidate_id,
                confirmed_by_user_id, confirmed_at, created_at, updated_at)
            SELECT :memoryId, workspace_id, :label, candidate_type,
                   COALESCE(CAST(:title AS TEXT), title),
                   COALESCE(CAST(:summary AS TEXT), summary),
                   COALESCE(CAST(:body AS TEXT), body),
                   'ACTIVE', source_ref_ids, related_entity_ids, id,
                   :userId, :now, :now, :now
            FROM memory_candidates WHERE id = :candidateId
            """)
        .setParameter("memoryId", memoryId)
        .setParameter("label", label)
        .setParameter("title", editedTitle)
        .setParameter("summary", editedSummary)
        .setParameter("body", editedBody)
        .setParameter("userId", userId)
        .setParameter("now", now)
        .setParameter("candidateId", candidateId)
        .executeUpdate();
    insertReviewAction(
        workspaceId,
        candidateId,
        actionType,
        userId,
        editedTitle,
        editedSummary,
        editedBody,
        null,
        null);
    outboxAppender.append(
        workspaceId,
        AGGREGATE_MEMORY,
        memoryId.toString(),
        EVENT_CONFIRMED,
        Map.of("candidate_id", candidateId.toString()));

    return memoryRepository
        .findDetailById(memoryId)
        .orElseThrow(() -> new BusinessException(MemoryErrorCode.MEMORY_NOT_FOUND));
  }

  @Override
  @Transactional
  public void reject(UUID candidateId, UUID userId, String reason) {
    UUID workspaceId = requireCandidateMembership(candidateId, userId);
    lockProposedCandidate(candidateId);

    String rejectionReason = emptyToNull(reason);
    markCandidateReviewed(candidateId, "REJECTED", userId, rejectionReason, Instant.now());
    insertReviewAction(
        workspaceId, candidateId, ACTION_REJECT, userId, null, null, null, rejectionReason, null);
  }

  @Override
  @Transactional
  public void merge(UUID candidateId, UUID targetMemoryId, UUID userId) {
    UUID candidateWorkspaceId = findCandidateWorkspace(candidateId);
    UUID targetWorkspaceId = findMemoryWorkspace(targetMemoryId);
    if (!candidateWorkspaceId.equals(targetWorkspaceId)) {
      throw new BusinessException(CommonErrorCode.AUTH_FORBIDDEN);
    }
    requireMembership(candidateWorkspaceId, userId);
    lockProposedCandidate(candidateId);
    lockMemory(targetMemoryId);

    markCandidateReviewed(candidateId, "MERGED", userId, null, Instant.now());
    insertReviewAction(
        candidateWorkspaceId,
        candidateId,
        ACTION_MERGE,
        userId,
        null,
        null,
        null,
        null,
        targetMemoryId);
  }

  @Override
  @Transactional
  public void expire(UUID candidateId, UUID userId) {
    UUID workspaceId = requireCandidateMembership(candidateId, userId);
    lockProposedCandidate(candidateId);

    markCandidateReviewed(candidateId, "EXPIRED", userId, null, Instant.now());
    insertReviewAction(
        workspaceId, candidateId, ACTION_EXPIRE, userId, null, null, null, null, null);
  }

  @Override
  @Transactional
  public void resolve(UUID resolvedMemoryId, UUID resolvingMemoryId, UUID userId) {
    UUID workspaceId = findMemoryWorkspace(resolvedMemoryId);
    UUID resolvingWorkspaceId = findMemoryWorkspace(resolvingMemoryId);
    if (!workspaceId.equals(resolvingWorkspaceId)) {
      throw new BusinessException(CommonErrorCode.AUTH_FORBIDDEN);
    }
    requireMembership(workspaceId, userId);

    // 레거시와 같은 순서로 잠급니다(해결 대상 먼저). 반대 방향 요청이 동시에 오면 교착할 수 있지만 그것은
    // 레거시부터 있던 성질이라, 여기서 순서를 바꾸면 두 서버가 공유 DB에서 서로 다른 순서로 잠급니다.
    lockMemory(resolvedMemoryId);
    lockMemory(resolvingMemoryId);

    relationWriter.link(
        new EntityRelationCommand(
            workspaceId,
            EntityType.MEMORY,
            resolvingMemoryId,
            RelationType.RESOLVES,
            EntityType.MEMORY,
            resolvedMemoryId));
    entityManager
        .createNativeQuery(
            "UPDATE confirmed_memories SET status = 'ARCHIVED', updated_at = :now WHERE id = :id")
        .setParameter("now", Instant.now())
        .setParameter("id", resolvedMemoryId)
        .executeUpdate();
    outboxAppender.append(
        workspaceId,
        AGGREGATE_MEMORY,
        resolvedMemoryId.toString(),
        EVENT_UPDATED,
        Map.of("resolving_memory_id", resolvingMemoryId.toString()));
  }

  private UUID requireCandidateMembership(UUID candidateId, UUID userId) {
    UUID workspaceId = findCandidateWorkspace(candidateId);
    requireMembership(workspaceId, userId);
    return workspaceId;
  }

  private void requireMembership(UUID workspaceId, UUID userId) {
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(CommonErrorCode.AUTH_FORBIDDEN);
    }
  }

  private UUID findCandidateWorkspace(UUID candidateId) {
    return entityManager
        .createQuery("select c.workspaceId from MemoryCandidate c where c.id = :id", UUID.class)
        .setParameter("id", candidateId)
        .getResultStream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(MemoryErrorCode.MEMORY_CANDIDATE_NOT_FOUND));
  }

  private UUID findMemoryWorkspace(UUID memoryId) {
    return entityManager
        .createQuery(
            "select m.workspaceId from ConfirmedMemory m where m.id = :id and m.deletedAt is null",
            UUID.class)
        .setParameter("id", memoryId)
        .getResultStream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(MemoryErrorCode.MEMORY_NOT_FOUND));
  }

  private void lockProposedCandidate(UUID candidateId) {
    List<?> rows =
        entityManager
            .createNativeQuery("SELECT status FROM memory_candidates WHERE id = :id FOR UPDATE")
            .setParameter("id", candidateId)
            .getResultList();
    if (rows.isEmpty()) {
      throw new BusinessException(MemoryErrorCode.MEMORY_CANDIDATE_NOT_FOUND);
    }
    if (!STATUS_PROPOSED.equals(rows.getFirst())) {
      throw new BusinessException(MemoryErrorCode.MEMORY_INVALID_STATE);
    }
  }

  private void lockMemory(UUID memoryId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "SELECT id FROM confirmed_memories WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
            .setParameter("id", memoryId)
            .getResultList();
    if (rows.isEmpty()) {
      throw new BusinessException(MemoryErrorCode.MEMORY_NOT_FOUND);
    }
  }

  /**
   * 레거시 {@code MarkCandidateReviewed}와 같은 컬럼을 씁니다. {@code rejection_reason}은 reject가 아닌 액션에서도 함께
   * 갱신되며 그때는 {@code NULL}입니다.
   */
  private void markCandidateReviewed(
      UUID candidateId, String status, UUID reviewerId, String rejectionReason, Instant now) {
    entityManager
        .createNativeQuery(
            """
            UPDATE memory_candidates
            SET status = :status, reviewed_at = :now, reviewed_by_user_id = :reviewerId,
                rejection_reason = :reason, updated_at = :now
            WHERE id = :id
            """)
        .setParameter("status", status)
        .setParameter("now", now)
        .setParameter("reviewerId", reviewerId)
        .setParameter("reason", rejectionReason)
        .setParameter("id", candidateId)
        .executeUpdate();
  }

  private void insertReviewAction(
      UUID workspaceId,
      UUID candidateId,
      String actionType,
      UUID reviewerId,
      String editedTitle,
      String editedSummary,
      String editedBody,
      String rejectionReason,
      UUID mergeTargetMemoryId) {
    entityManager
        .createNativeQuery(
            """
            INSERT INTO review_actions (
                id, workspace_id, candidate_id, action_type, reviewer_user_id,
                edited_title, edited_summary, edited_body, rejection_reason,
                merge_target_memory_id, created_at)
            VALUES (:id, :workspaceId, :candidateId, :actionType, :reviewerId,
                    :title, :summary, :body, :reason, :target, :now)
            """)
        // PK는 앱에서 만듭니다(docs/rules/persistence.md, 레거시 InsertReviewAction도 uuid.New()).
        .setParameter("id", UUID.randomUUID())
        .setParameter("workspaceId", workspaceId)
        .setParameter("candidateId", candidateId)
        .setParameter("actionType", actionType)
        .setParameter("reviewerId", reviewerId)
        .setParameter("title", editedTitle)
        .setParameter("summary", editedSummary)
        .setParameter("body", editedBody)
        .setParameter("reason", rejectionReason)
        .setParameter("target", mergeTargetMemoryId)
        .setParameter("now", Instant.now())
        .executeUpdate();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
