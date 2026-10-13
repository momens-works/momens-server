package works.momens.server.memory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.memory.MemoryCandidateDetail;
import works.momens.server.memory.MemoryCandidateReader;
import works.momens.server.memory.MemorySeedSql;

/** worker 형태의 데이터를 네이티브 SQL로 구성해 상태 필터 없는 중요도 우선 정렬을 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MemoryCandidateReaderImpl.class})
@DisplayName("MemoryCandidateReader 통합 테스트")
class MemoryCandidateReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MemoryCandidateReader memoryCandidateReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("레거시가 저장한 필드를 그대로 반환한다")
  void listDetailsReturnsStoredFields() {
    Fixture fixture = new Fixture();
    UUID reviewer = UUID.randomUUID();
    UUID sourceRef = UUID.randomUUID();
    UUID relatedEntity = UUID.randomUUID();
    UUID candidateId =
        fixture.insertCandidate(
            "결제 실패 재시도 정책", 0.82d, 0.91d, "CONFIRMED", Instant.parse("2026-07-01T09:00:00Z"));
    setStoredFields(
        candidateId,
        "SUG-0001",
        "요약",
        "본문",
        MemorySeedSql.uuidArray(sourceRef),
        MemorySeedSql.uuidArray(relatedEntity),
        "MINSU",
        Instant.parse("2026-07-02T09:00:00Z"),
        reviewer,
        "중복 제안",
        Instant.parse("2026-08-01T00:00:00Z"),
        "{\"origin\": \"slack\"}");

    MemoryCandidateDetail detail = fixture.onlyDetail();

    assertThat(detail.id()).isEqualTo(candidateId);
    assertThat(detail.workspaceId()).isEqualTo(fixture.workspaceId);
    assertThat(detail.label()).isEqualTo("SUG-0001");
    assertThat(detail.candidateType()).isEqualTo("DECISION");
    assertThat(detail.title()).isEqualTo("결제 실패 재시도 정책");
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.body()).isEqualTo("본문");
    assertThat(detail.confidence()).isEqualTo(0.82d);
    assertThat(detail.importance()).isEqualTo(0.91d);
    assertThat(detail.status()).isEqualTo("CONFIRMED");
    assertThat(detail.sourceRefIds()).containsExactly(sourceRef);
    assertThat(detail.relatedEntityIds()).containsExactly(relatedEntity);
    assertThat(detail.proposedBy()).isEqualTo("MINSU");
    assertThat(detail.reviewedAt()).isEqualTo(Instant.parse("2026-07-02T09:00:00Z"));
    assertThat(detail.reviewedByUserId()).isEqualTo(reviewer);
    assertThat(detail.rejectionReason()).isEqualTo("중복 제안");
    assertThat(detail.expiresAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(detail.metadata()).containsExactly(Map.entry("origin", "slack"));
    assertThat(detail.createdAt()).isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
    assertThat(detail.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("선택 필드가 없으면 비우고, 나머지는 레거시 DEFAULT를 따른다")
  void listDetailsLeavesOptionalFieldsEmptyWhenNotStored() {
    Fixture fixture = new Fixture();
    fixture.insertCandidate("제목만 있는 후보", null, null, null, Instant.parse("2026-07-01T09:00:00Z"));

    MemoryCandidateDetail detail = fixture.onlyDetail();

    assertThat(detail.label()).isNull();
    assertThat(detail.summary()).isNull();
    assertThat(detail.body()).isNull();
    assertThat(detail.confidence()).isNull();
    assertThat(detail.importance()).isNull();
    assertThat(detail.reviewedAt()).isNull();
    assertThat(detail.reviewedByUserId()).isNull();
    assertThat(detail.rejectionReason()).isNull();
    assertThat(detail.expiresAt()).isNull();
    assertThat(detail.metadata()).isNull();
    assertThat(detail.status()).isEqualTo("PROPOSED");
    assertThat(detail.proposedBy()).isEqualTo("CURATOR");
  }

  @Test
  @DisplayName("id 배열은 컬럼이 비어 있든 빈 배열이든 똑같이 빈 목록으로 반환한다")
  void idArraysCollapseNullAndEmptyToEmptyList() {
    Fixture fixture = new Fixture();
    UUID nullArrays =
        fixture.insertCandidate(
            "컬럼이 빈 후보", null, 0.9d, null, Instant.parse("2026-07-02T09:00:00Z"));
    UUID emptyArrays =
        fixture.insertCandidate("빈 배열 후보", null, 0.8d, null, Instant.parse("2026-07-01T09:00:00Z"));
    setIdArrays(emptyArrays, MemorySeedSql.uuidArray(), MemorySeedSql.uuidArray());

    List<MemoryCandidateDetail> details = fixture.details();

    assertThat(details)
        .extracting(MemoryCandidateDetail::id)
        .containsExactly(nullArrays, emptyArrays);
    assertThat(details)
        .allSatisfy(
            detail -> {
              assertThat(detail.sourceRefIds()).isEmpty();
              assertThat(detail.relatedEntityIds()).isEmpty();
            });
  }

  @Test
  @DisplayName("중요도 내림차순으로 정렬하고 중요도가 없는 후보를 뒤로 보낸다")
  void listDetailsOrdersByImportanceThenCreatedAt() {
    Fixture fixture = new Fixture();
    // 중요도가 없는 후보를 가장 나중에 만들어, 생성 시각만으로 정렬했다면 앞에 오도록 둔다.
    UUID lowImportance =
        fixture.insertCandidate("낮음", null, 0.2d, null, Instant.parse("2026-07-01T09:00:00Z"));
    UUID highImportance =
        fixture.insertCandidate("높음", null, 0.9d, null, Instant.parse("2026-07-02T09:00:00Z"));
    UUID noImportance =
        fixture.insertCandidate("없음", null, null, null, Instant.parse("2026-07-03T09:00:00Z"));

    assertThat(fixture.details())
        .extracting(MemoryCandidateDetail::id)
        .containsExactly(highImportance, lowImportance, noImportance);
  }

  @Test
  @DisplayName("중요도가 같으면 생성 시각 내림차순으로 정렬한다")
  void listDetailsBreaksImportanceTiesByCreatedAt() {
    Fixture fixture = new Fixture();
    UUID earlier =
        fixture.insertCandidate("먼저", null, 0.5d, null, Instant.parse("2026-07-01T09:00:00Z"));
    UUID later =
        fixture.insertCandidate("나중", null, 0.5d, null, Instant.parse("2026-07-02T09:00:00Z"));

    assertThat(fixture.details())
        .extracting(MemoryCandidateDetail::id)
        .containsExactly(later, earlier);
  }

  @Test
  @DisplayName("상태로 거르지 않아 거절·만료된 후보도 목록에 담는다")
  void listDetailsKeepsEveryStatus() {
    Fixture fixture = new Fixture();
    fixture.insertCandidate("제안", null, 0.4d, "PROPOSED", Instant.parse("2026-07-01T09:00:00Z"));
    fixture.insertCandidate("거절", null, 0.3d, "REJECTED", Instant.parse("2026-07-02T09:00:00Z"));
    fixture.insertCandidate("만료", null, 0.2d, "EXPIRED", Instant.parse("2026-07-03T09:00:00Z"));
    fixture.insertCandidate("병합", null, 0.1d, "MERGED", Instant.parse("2026-07-04T09:00:00Z"));

    assertThat(fixture.details())
        .extracting(MemoryCandidateDetail::status)
        .containsExactly("PROPOSED", "REJECTED", "EXPIRED", "MERGED");
  }

  @Test
  @DisplayName("다른 워크스페이스의 후보는 제외한다")
  void listDetailsExcludesOtherWorkspaces() {
    Fixture fixture = new Fixture();
    Fixture other = new Fixture();
    UUID own =
        fixture.insertCandidate("우리 것", null, 0.5d, null, Instant.parse("2026-07-01T09:00:00Z"));
    other.insertCandidate("남의 것", null, 0.9d, null, Instant.parse("2026-07-02T09:00:00Z"));

    assertThat(fixture.details()).extracting(MemoryCandidateDetail::id).containsExactly(own);
  }

  @Test
  @DisplayName("후보가 없는 워크스페이스는 빈 목록을 반환한다")
  void listDetailsIsEmptyForWorkspaceWithoutCandidates() {
    assertThat(memoryCandidateReader.listDetailsByWorkspaceId(UUID.randomUUID())).isEmpty();
  }

  private final class Fixture {

    private final UUID workspaceId;

    private Fixture() {
      this.workspaceId = UUID.randomUUID();
    }

    private UUID insertCandidate(
        String title, Double confidence, Double importance, String status, Instant createdAt) {
      UUID id = UUID.randomUUID();
      entityManager
          .getEntityManager()
          .createNativeQuery(
              "INSERT INTO memory_candidates"
                  + " (id, workspace_id, candidate_type, title, confidence, importance, status,"
                  + " created_at, updated_at)"
                  + " VALUES (?1, ?2, 'DECISION', ?3, ?4, ?5, COALESCE(?6, 'PROPOSED'), ?7, ?7)")
          .setParameter(1, id)
          .setParameter(2, workspaceId)
          .setParameter(3, title)
          .setParameter(4, confidence)
          .setParameter(5, importance)
          .setParameter(6, status)
          .setParameter(7, createdAt)
          .executeUpdate();
      entityManager.clear();
      return id;
    }

    private List<MemoryCandidateDetail> details() {
      return memoryCandidateReader.listDetailsByWorkspaceId(workspaceId);
    }

    private MemoryCandidateDetail onlyDetail() {
      List<MemoryCandidateDetail> details = details();
      assertThat(details).hasSize(1);
      return details.getFirst();
    }
  }

  private void setStoredFields(
      UUID candidateId,
      String label,
      String summary,
      String body,
      String sourceRefIds,
      String relatedEntityIds,
      String proposedBy,
      Instant reviewedAt,
      UUID reviewedByUserId,
      String rejectionReason,
      Instant expiresAt,
      String metadata) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE memory_candidates SET label = ?1, summary = ?2, body = ?3,"
                + " source_ref_ids = CAST(?4 AS uuid[]), related_entity_ids = CAST(?5 AS uuid[]),"
                + " proposed_by = ?6, reviewed_at = ?7, reviewed_by_user_id = ?8,"
                + " rejection_reason = ?9, expires_at = ?10, metadata = CAST(?11 AS jsonb)"
                + " WHERE id = ?12")
        .setParameter(1, label)
        .setParameter(2, summary)
        .setParameter(3, body)
        .setParameter(4, sourceRefIds)
        .setParameter(5, relatedEntityIds)
        .setParameter(6, proposedBy)
        .setParameter(7, reviewedAt)
        .setParameter(8, reviewedByUserId)
        .setParameter(9, rejectionReason)
        .setParameter(10, expiresAt)
        .setParameter(11, metadata)
        .setParameter(12, candidateId)
        .executeUpdate();
    entityManager.clear();
  }

  private void setIdArrays(UUID candidateId, String sourceRefIds, String relatedEntityIds) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE memory_candidates SET source_ref_ids = CAST(?1 AS uuid[]),"
                + " related_entity_ids = CAST(?2 AS uuid[]) WHERE id = ?3")
        .setParameter(1, sourceRefIds)
        .setParameter(2, relatedEntityIds)
        .setParameter(3, candidateId)
        .executeUpdate();
    entityManager.clear();
  }
}
