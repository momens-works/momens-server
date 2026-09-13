package works.momens.server.memory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.context.EntityRelationCommand;
import works.momens.server.context.EntityRelationWriter;
import works.momens.server.context.EntityType;
import works.momens.server.context.RelationType;
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.memory.MemorySeedSql;
import works.momens.server.memory.MemoryWriter;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 후보 리뷰·메모리 해결 write 계약을 레거시 {@code memory/service.go} 기준으로 검증합니다.
 *
 * <p>상태 전이와 review action 기록, 확정 메모리에 담기는 컬럼, 권한·상태 검사 순서, projection 이벤트 유무를 확인합니다. 워크스페이스·라벨·관계·
 * outbox는 다른 모듈의 public API라 목으로 두고, 이 모듈이 실제로 쓰는 세 테이블만 실제 PostgreSQL로 검증합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MemoryWriterImpl.class})
@DisplayName("MemoryWriter 통합 테스트")
class MemoryWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MemoryWriter memoryWriter;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;
  @MockitoBean private LabelAllocator labelAllocator;
  @MockitoBean private EntityRelationWriter relationWriter;
  @MockitoBean private OutboxAppender outboxAppender;

  private UUID workspaceId;
  private UUID userId;

  @BeforeEach
  void setUpWorkspace() {
    workspaceId = MemorySeedSql.insertWorkspace(entityManager);
    userId = MemorySeedSql.insertUser(entityManager);
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  @Test
  @DisplayName("확정은 후보를 CONFIRMED로 바꾸고 MEM 라벨을 붙인 메모리를 만든다")
  void confirmCreatesLabelledMemory() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    when(labelAllocator.allocateMemoryLabel(workspaceId)).thenReturn("MEM-0001");

    ConfirmedMemoryDetail memory = memoryWriter.confirm(candidateId, userId);

    assertThat(memory.label()).isEqualTo("MEM-0001");
    assertThat(memory.status()).isEqualTo("ACTIVE");
    assertThat(memory.memoryType()).isEqualTo("DECISION");
    assertThat(memory.title()).isEqualTo("결제 재시도는 3회로 고정한다");
    assertThat(memory.createdFromCandidateId()).isEqualTo(candidateId);
    assertThat(memory.confirmedByUserId()).isEqualTo(userId);
    assertThat(memory.confirmedAt()).isNotNull();
    assertThat(candidateStatus(entityManager, candidateId)).isEqualTo("CONFIRMED");
  }

  @Test
  @DisplayName("확정은 후보의 metadata를 옮기지 않는다")
  void confirmDoesNotCopyCandidateMetadata() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    setCandidateMetadata(entityManager, candidateId, "{\"extractor\": \"curator-v2\"}");
    when(labelAllocator.allocateMemoryLabel(workspaceId)).thenReturn("MEM-0001");

    ConfirmedMemoryDetail memory = memoryWriter.confirm(candidateId, userId);

    // 레거시는 확정 메모리의 metadata를 항상 NULL로 둡니다. 후보 metadata는 워커가 남긴 추출 정보입니다.
    assertThat(memory.metadata()).isNull();
  }

  @Test
  @DisplayName("확정은 CONFIRM review action과 memory.confirmed 이벤트를 남긴다")
  void confirmRecordsReviewActionAndOutboxEvent() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    when(labelAllocator.allocateMemoryLabel(workspaceId)).thenReturn("MEM-0001");

    ConfirmedMemoryDetail memory = memoryWriter.confirm(candidateId, userId);

    Object[] action = onlyReviewAction(entityManager, candidateId);
    assertThat(action[0]).isEqualTo("CONFIRM");
    assertThat(action[1]).isEqualTo(userId);
    assertThat(action[2]).isNull();
    assertThat(action[3]).isNull();
    assertThat(action[4]).isNull();
    verify(outboxAppender)
        .append(
            eq(workspaceId),
            eq("memory"),
            eq(memory.id().toString()),
            eq("memory.confirmed"),
            eq(Map.of("candidate_id", candidateId.toString())));
  }

  @Test
  @DisplayName("수정 후 확정은 비어 있지 않은 필드만 덮어쓰고 EDIT_AND_CONFIRM으로 남긴다")
  void editAndConfirmOverwritesOnlyNonEmptyFields() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    setCandidateText(entityManager, candidateId, "원래 요약", "원래 본문");
    when(labelAllocator.allocateMemoryLabel(workspaceId)).thenReturn("MEM-0002");

    ConfirmedMemoryDetail memory =
        memoryWriter.editAndConfirm(
            candidateId, userId, new MemoryWriter.CandidateEdits("고친 제목", "", null));

    assertThat(memory.title()).isEqualTo("고친 제목");
    assertThat(memory.summary()).isEqualTo("원래 요약");
    assertThat(memory.body()).isEqualTo("원래 본문");
    Object[] action = onlyReviewAction(entityManager, candidateId);
    assertThat(action[0]).isEqualTo("EDIT_AND_CONFIRM");
    assertThat(action[2]).isEqualTo("고친 제목");
    assertThat(action[3]).isNull();
    assertThat(action[4]).isNull();
  }

  @Test
  @DisplayName("모든 편집 필드가 비어 있어도 EDIT_AND_CONFIRM으로 남긴다")
  void editAndConfirmKeepsActionTypeWhenAllEditsAreEmpty() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    when(labelAllocator.allocateMemoryLabel(workspaceId)).thenReturn("MEM-0003");

    memoryWriter.editAndConfirm(
        candidateId, userId, new MemoryWriter.CandidateEdits(null, null, null));

    assertThat(onlyReviewAction(entityManager, candidateId)[0]).isEqualTo("EDIT_AND_CONFIRM");
  }

  @Test
  @DisplayName("거절은 사유를 후보와 review action에 함께 남기고 projection 이벤트는 남기지 않는다")
  void rejectStoresReasonWithoutProjection() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);

    memoryWriter.reject(candidateId, userId, "중복된 결정입니다");

    assertThat(candidateStatus(entityManager, candidateId)).isEqualTo("REJECTED");
    assertThat(candidateRejectionReason(entityManager, candidateId)).isEqualTo("중복된 결정입니다");
    Object[] action = onlyReviewAction(entityManager, candidateId);
    assertThat(action[0]).isEqualTo("REJECT");
    assertThat(action[5]).isEqualTo("중복된 결정입니다");
    verifyNoInteractions(outboxAppender);
  }

  @Test
  @DisplayName("빈 거절 사유는 NULL로 저장한다")
  void rejectStoresEmptyReasonAsNull() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);

    memoryWriter.reject(candidateId, userId, "");

    assertThat(candidateRejectionReason(entityManager, candidateId)).isNull();
  }

  @Test
  @DisplayName("병합은 후보를 MERGED로 바꾸고 대상 메모리를 review action에 남긴다")
  void mergeMarksCandidateAndRecordsTarget() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    UUID targetId = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");

    memoryWriter.merge(candidateId, targetId, userId);

    assertThat(candidateStatus(entityManager, candidateId)).isEqualTo("MERGED");
    Object[] action = onlyReviewAction(entityManager, candidateId);
    assertThat(action[0]).isEqualTo("MERGE");
    assertThat(action[6]).isEqualTo(targetId);
    // 레거시는 병합에 projection을 남기지 않습니다. 대상 메모리 내용이 바뀌지 않기 때문입니다.
    verifyNoInteractions(outboxAppender);
  }

  @Test
  @DisplayName("다른 워크스페이스의 메모리로는 병합할 수 없다")
  void mergeRejectsCrossWorkspaceTarget() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    UUID otherWorkspaceId = MemorySeedSql.insertWorkspace(entityManager);
    UUID targetId = MemorySeedSql.insertMemory(entityManager, otherWorkspaceId, "ACTIVE");

    assertThatThrownBy(() -> memoryWriter.merge(candidateId, targetId, userId))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
    assertThat(candidateStatus(entityManager, candidateId)).isEqualTo("PROPOSED");
  }

  @Test
  @DisplayName("만료는 후보를 EXPIRED로 바꾸고 EXPIRE review action을 남긴다")
  void expireMarksCandidate() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);

    memoryWriter.expire(candidateId, userId);

    assertThat(candidateStatus(entityManager, candidateId)).isEqualTo("EXPIRED");
    assertThat(onlyReviewAction(entityManager, candidateId)[0]).isEqualTo("EXPIRE");
  }

  @Test
  @DisplayName("PROPOSED가 아닌 후보는 리뷰할 수 없다")
  void reviewRequiresProposedCandidate() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    memoryWriter.expire(candidateId, userId);

    assertThatThrownBy(() -> memoryWriter.reject(candidateId, userId, null))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(MemoryErrorCode.MEMORY_INVALID_STATE);
  }

  @Test
  @DisplayName("비멤버에게는 후보 상태보다 권한 실패를 먼저 알린다")
  void membershipIsCheckedBeforeCandidateState() {
    UUID candidateId = MemorySeedSql.insertProposedCandidate(entityManager, workspaceId);
    memoryWriter.expire(candidateId, userId);
    UUID stranger = MemorySeedSql.insertUser(entityManager);
    when(workspaceMembershipReader.roleOf(workspaceId, stranger)).thenReturn(Optional.empty());

    // 레거시는 멤버십(403)을 잠금·상태 검사(409)보다 먼저 봅니다. 순서가 바뀌면 비멤버가 후보 상태를 알게 됩니다.
    assertThatThrownBy(() -> memoryWriter.reject(candidateId, stranger, null))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  @DisplayName("없는 후보는 후보 not found로 알린다")
  void missingCandidateIsReported() {
    assertThatThrownBy(() -> memoryWriter.confirm(UUID.randomUUID(), userId))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(MemoryErrorCode.MEMORY_CANDIDATE_NOT_FOUND);
  }

  @Test
  @DisplayName("해결은 RESOLVES 관계를 남기고 해결된 메모리를 ARCHIVED로 바꾼다")
  void resolveLinksAndArchives() {
    UUID resolved = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");
    UUID resolving = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");

    memoryWriter.resolve(resolved, resolving, userId);

    assertThat(memoryStatus(entityManager, resolved)).isEqualTo("ARCHIVED");
    assertThat(memoryStatus(entityManager, resolving)).isEqualTo("ACTIVE");
    ArgumentCaptor<EntityRelationCommand> command =
        ArgumentCaptor.forClass(EntityRelationCommand.class);
    verify(relationWriter).link(command.capture());
    // 방향은 해결하는 쪽 -> 해결된 쪽입니다.
    assertThat(command.getValue().fromEntityId()).isEqualTo(resolving);
    assertThat(command.getValue().toEntityId()).isEqualTo(resolved);
    assertThat(command.getValue().relationType()).isEqualTo(RelationType.RESOLVES);
    assertThat(command.getValue().fromEntityType()).isEqualTo(EntityType.MEMORY);
    verify(outboxAppender)
        .append(
            eq(workspaceId),
            eq("memory"),
            eq(resolved.toString()),
            eq("memory.updated"),
            eq(Map.of("resolving_memory_id", resolving.toString())));
  }

  @Test
  @DisplayName("워크스페이스가 다른 메모리끼리는 해결할 수 없다")
  void resolveRejectsCrossWorkspacePair() {
    UUID resolved = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");
    UUID resolving =
        MemorySeedSql.insertMemory(
            entityManager, MemorySeedSql.insertWorkspace(entityManager), "ACTIVE");

    assertThatThrownBy(() -> memoryWriter.resolve(resolved, resolving, userId))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
    assertThat(memoryStatus(entityManager, resolved)).isEqualTo("ACTIVE");
    verify(relationWriter, never()).link(any());
  }

  @Test
  @DisplayName("소프트 삭제된 메모리는 해결 대상이 될 수 없다")
  void resolveIgnoresSoftDeletedMemory() {
    UUID resolved = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");
    UUID resolving = MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE");
    softDeleteMemory(entityManager, resolving);

    assertThatThrownBy(() -> memoryWriter.resolve(resolved, resolving, userId))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(MemoryErrorCode.MEMORY_NOT_FOUND);
  }

  private String candidateStatus(TestEntityManager em, UUID candidateId) {
    return (String)
        em.getEntityManager()
            .createNativeQuery("SELECT status FROM memory_candidates WHERE id = ?1")
            .setParameter(1, candidateId)
            .getSingleResult();
  }

  private String candidateRejectionReason(TestEntityManager em, UUID candidateId) {
    return (String)
        em.getEntityManager()
            .createNativeQuery("SELECT rejection_reason FROM memory_candidates WHERE id = ?1")
            .setParameter(1, candidateId)
            .getSingleResult();
  }

  private String memoryStatus(TestEntityManager em, UUID memoryId) {
    return (String)
        em.getEntityManager()
            .createNativeQuery("SELECT status FROM confirmed_memories WHERE id = ?1")
            .setParameter(1, memoryId)
            .getSingleResult();
  }

  /**
   * {@code action_type, reviewer_user_id, edited_title, edited_summary, edited_body,
   * rejection_reason, merge_target_memory_id} 순입니다.
   */
  private Object[] onlyReviewAction(TestEntityManager em, UUID candidateId) {
    List<?> rows =
        em.getEntityManager()
            .createNativeQuery(
                """
                SELECT action_type, reviewer_user_id, edited_title, edited_summary, edited_body,
                       rejection_reason, merge_target_memory_id
                FROM review_actions WHERE candidate_id = ?1
                """)
            .setParameter(1, candidateId)
            .getResultList();
    assertThat(rows).hasSize(1);
    return (Object[]) rows.getFirst();
  }

  private void setCandidateMetadata(TestEntityManager em, UUID candidateId, String metadata) {
    em.getEntityManager()
        .createNativeQuery(
            "UPDATE memory_candidates SET metadata = CAST(?1 AS jsonb) WHERE id = ?2")
        .setParameter(1, metadata)
        .setParameter(2, candidateId)
        .executeUpdate();
  }

  private void setCandidateText(
      TestEntityManager em, UUID candidateId, String summary, String body) {
    em.getEntityManager()
        .createNativeQuery("UPDATE memory_candidates SET summary = ?1, body = ?2 WHERE id = ?3")
        .setParameter(1, summary)
        .setParameter(2, body)
        .setParameter(3, candidateId)
        .executeUpdate();
  }

  private void softDeleteMemory(TestEntityManager em, UUID memoryId) {
    em.getEntityManager()
        .createNativeQuery("UPDATE confirmed_memories SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, memoryId)
        .executeUpdate();
  }
}
