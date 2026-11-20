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
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.memory.ConfirmedMemoryReader;
import works.momens.server.memory.MemorySeedSql;

/** 레거시 형태의 데이터를 네이티브 SQL로 구성해 상태와 독립적인 소프트 삭제 필터를 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ConfirmedMemoryReaderImpl.class})
@DisplayName("ConfirmedMemoryReader 통합 테스트")
class ConfirmedMemoryReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private ConfirmedMemoryReader confirmedMemoryReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("레거시가 저장한 필드를 그대로 반환한다")
  void listDetailsReturnsStoredFields() {
    Fixture fixture = new Fixture();
    UUID confirmer = UUID.randomUUID();
    UUID invalidator = UUID.randomUUID();
    UUID candidateId = MemorySeedSql.insertCandidate(entityManager, fixture.workspaceId);
    UUID sourceRef = UUID.randomUUID();
    UUID relatedEntity = UUID.randomUUID();
    UUID memoryId =
        fixture.insertMemory(
            "결제 재시도는 3회로 고정한다", "INVALIDATED", Instant.parse("2026-07-01T09:00:00Z"));
    setStoredFields(
        memoryId,
        "MEM-0001",
        "요약",
        "본문",
        MemorySeedSql.uuidArray(sourceRef),
        MemorySeedSql.uuidArray(relatedEntity),
        candidateId,
        confirmer,
        Instant.parse("2026-07-02T09:00:00Z"),
        Instant.parse("2026-07-03T00:00:00Z"),
        Instant.parse("2026-12-31T00:00:00Z"),
        Instant.parse("2026-07-10T09:00:00Z"),
        invalidator,
        "정책이 바뀌었습니다",
        "{\"origin\": \"slack\"}");

    ConfirmedMemoryDetail detail = fixture.onlyDetail();

    assertThat(detail.id()).isEqualTo(memoryId);
    assertThat(detail.workspaceId()).isEqualTo(fixture.workspaceId);
    assertThat(detail.label()).isEqualTo("MEM-0001");
    assertThat(detail.memoryType()).isEqualTo("DECISION");
    assertThat(detail.title()).isEqualTo("결제 재시도는 3회로 고정한다");
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.body()).isEqualTo("본문");
    assertThat(detail.status()).isEqualTo("INVALIDATED");
    assertThat(detail.sourceRefIds()).containsExactly(sourceRef);
    assertThat(detail.relatedEntityIds()).containsExactly(relatedEntity);
    assertThat(detail.createdFromCandidateId()).isEqualTo(candidateId);
    assertThat(detail.confirmedByUserId()).isEqualTo(confirmer);
    assertThat(detail.confirmedAt()).isEqualTo(Instant.parse("2026-07-02T09:00:00Z"));
    assertThat(detail.validFrom()).isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
    assertThat(detail.validUntil()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
    assertThat(detail.invalidatedAt()).isEqualTo(Instant.parse("2026-07-10T09:00:00Z"));
    assertThat(detail.invalidatedByUserId()).isEqualTo(invalidator);
    assertThat(detail.invalidationReason()).isEqualTo("정책이 바뀌었습니다");
    assertThat(detail.metadata()).containsExactly(Map.entry("origin", "slack"));
    assertThat(detail.createdAt()).isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
    assertThat(detail.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("선택 필드가 없으면 비우고, 나머지는 레거시 DEFAULT를 따른다")
  void listDetailsLeavesOptionalFieldsEmptyWhenNotStored() {
    Fixture fixture = new Fixture();
    fixture.insertMemory("제목만 있는 메모리", null, Instant.parse("2026-07-01T09:00:00Z"));

    ConfirmedMemoryDetail detail = fixture.onlyDetail();

    assertThat(detail.label()).isNull();
    assertThat(detail.summary()).isNull();
    assertThat(detail.body()).isNull();
    assertThat(detail.createdFromCandidateId()).isNull();
    assertThat(detail.confirmedByUserId()).isNull();
    assertThat(detail.confirmedAt()).isNull();
    assertThat(detail.validFrom()).isNull();
    assertThat(detail.validUntil()).isNull();
    assertThat(detail.invalidatedAt()).isNull();
    assertThat(detail.invalidatedByUserId()).isNull();
    assertThat(detail.invalidationReason()).isNull();
    assertThat(detail.metadata()).isNull();
    // id 배열은 컬럼이 비어 있어도 빈 목록이다. 레거시가 SQL NULL과 빈 배열을 모두 키 생략으로 내보내 둘을 구분하지 않는다.
    assertThat(detail.sourceRefIds()).isEmpty();
    assertThat(detail.relatedEntityIds()).isEmpty();
    assertThat(detail.status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("생성 시각 내림차순으로 정렬한다")
  void listDetailsReturnsNewestFirst() {
    Fixture fixture = new Fixture();
    UUID earlier = fixture.insertMemory("먼저", null, Instant.parse("2026-07-01T09:00:00Z"));
    UUID later = fixture.insertMemory("나중", null, Instant.parse("2026-07-02T09:00:00Z"));

    assertThat(fixture.details())
        .extracting(ConfirmedMemoryDetail::id)
        .containsExactly(later, earlier);
  }

  @Test
  @DisplayName("소프트 삭제된 메모리는 목록에서 제외한다")
  void softDeletedMemoryIsGoneFromTheList() {
    Fixture fixture = new Fixture();
    UUID live = fixture.insertMemory("살아 있는 메모리", null, Instant.parse("2026-07-01T09:00:00Z"));
    UUID deleted =
        fixture.insertMemory("지워진 메모리", "DELETED", Instant.parse("2026-07-02T09:00:00Z"));
    softDelete(deleted);

    assertThat(fixture.details()).extracting(ConfirmedMemoryDetail::id).containsExactly(live);
  }

  @Test
  @DisplayName("상태로 거르지 않아 무효화·보관된 메모리도 목록에 담는다")
  void listDetailsKeepsEveryLiveStatus() {
    Fixture fixture = new Fixture();
    fixture.insertMemory("보관", "ARCHIVED", Instant.parse("2026-07-03T09:00:00Z"));
    fixture.insertMemory("무효", "INVALIDATED", Instant.parse("2026-07-02T09:00:00Z"));
    fixture.insertMemory("활성", "ACTIVE", Instant.parse("2026-07-01T09:00:00Z"));

    assertThat(fixture.details())
        .extracting(ConfirmedMemoryDetail::status)
        .containsExactly("ARCHIVED", "INVALIDATED", "ACTIVE");
  }

  @Test
  @DisplayName("상태만 DELETED이고 소프트 삭제되지 않은 메모리는 목록에 남는다")
  void deletedStatusWithoutDeletedAtStaysInTheList() {
    Fixture fixture = new Fixture();
    UUID statusOnly =
        fixture.insertMemory("상태만 DELETED", "DELETED", Instant.parse("2026-07-01T09:00:00Z"));

    // 조회 기준은 상태값이 아니라 deleted_at 컬럼이다. 레거시 삭제가 둘을 함께 바꾸므로 실제로는 갈리지 않지만,
    // 어느 쪽이 필터인지는 계약이다.
    assertThat(fixture.details()).extracting(ConfirmedMemoryDetail::id).containsExactly(statusOnly);
  }

  @Test
  @DisplayName("다른 워크스페이스의 메모리는 제외한다")
  void listDetailsExcludesOtherWorkspaces() {
    Fixture fixture = new Fixture();
    Fixture other = new Fixture();
    UUID own = fixture.insertMemory("우리 것", null, Instant.parse("2026-07-01T09:00:00Z"));
    other.insertMemory("남의 것", null, Instant.parse("2026-07-02T09:00:00Z"));

    assertThat(fixture.details()).extracting(ConfirmedMemoryDetail::id).containsExactly(own);
  }

  @Test
  @DisplayName("메모리가 없는 워크스페이스는 빈 목록을 반환한다")
  void listDetailsIsEmptyForWorkspaceWithoutMemories() {
    assertThat(confirmedMemoryReader.listDetailsByWorkspaceId(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("메모리가 속한 워크스페이스 식별자를 반환한다")
  void findWorkspaceIdReturnsWorkspaceOfMemory() {
    Fixture fixture = new Fixture();
    UUID memoryId = fixture.insertMemory("결제 재시도 정책", null, Instant.parse("2026-07-01T09:00:00Z"));

    assertThat(confirmedMemoryReader.findWorkspaceId(memoryId)).contains(fixture.workspaceId);
  }

  @Test
  @DisplayName("소프트 삭제되었거나 존재하지 않는 메모리는 빈 값을 반환한다")
  void findWorkspaceIdIsEmptyForSoftDeletedOrUnknownMemory() {
    Fixture fixture = new Fixture();
    UUID deleted = fixture.insertMemory("삭제된 메모리", null, Instant.parse("2026-07-01T09:00:00Z"));
    softDelete(deleted);

    assertThat(confirmedMemoryReader.findWorkspaceId(deleted)).isEmpty();
    assertThat(confirmedMemoryReader.findWorkspaceId(UUID.randomUUID())).isEmpty();
  }

  private final class Fixture {

    private final UUID workspaceId;

    private Fixture() {
      this.workspaceId = UUID.randomUUID();
    }

    private UUID insertMemory(String title, String status, Instant createdAt) {
      UUID id = UUID.randomUUID();
      entityManager
          .getEntityManager()
          .createNativeQuery(
              "INSERT INTO confirmed_memories"
                  + " (id, workspace_id, memory_type, title, status, created_at, updated_at)"
                  + " VALUES (?1, ?2, 'DECISION', ?3, COALESCE(?4, 'ACTIVE'), ?5, ?5)")
          .setParameter(1, id)
          .setParameter(2, workspaceId)
          .setParameter(3, title)
          .setParameter(4, status)
          .setParameter(5, createdAt)
          .executeUpdate();
      entityManager.clear();
      return id;
    }

    private List<ConfirmedMemoryDetail> details() {
      return confirmedMemoryReader.listDetailsByWorkspaceId(workspaceId);
    }

    private ConfirmedMemoryDetail onlyDetail() {
      List<ConfirmedMemoryDetail> details = details();
      assertThat(details).hasSize(1);
      return details.getFirst();
    }
  }

  private void setStoredFields(
      UUID memoryId,
      String label,
      String summary,
      String body,
      String sourceRefIds,
      String relatedEntityIds,
      UUID createdFromCandidateId,
      UUID confirmedByUserId,
      Instant confirmedAt,
      Instant validFrom,
      Instant validUntil,
      Instant invalidatedAt,
      UUID invalidatedByUserId,
      String invalidationReason,
      String metadata) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE confirmed_memories SET label = ?1, summary = ?2, body = ?3,"
                + " source_ref_ids = CAST(?4 AS uuid[]), related_entity_ids = CAST(?5 AS uuid[]),"
                + " created_from_candidate_id = ?6, confirmed_by_user_id = ?7, confirmed_at = ?8,"
                + " valid_from = ?9, valid_until = ?10, invalidated_at = ?11,"
                + " invalidated_by_user_id = ?12, invalidation_reason = ?13,"
                + " metadata = CAST(?14 AS jsonb)"
                + " WHERE id = ?15")
        .setParameter(1, label)
        .setParameter(2, summary)
        .setParameter(3, body)
        .setParameter(4, sourceRefIds)
        .setParameter(5, relatedEntityIds)
        .setParameter(6, createdFromCandidateId)
        .setParameter(7, confirmedByUserId)
        .setParameter(8, confirmedAt)
        .setParameter(9, validFrom)
        .setParameter(10, validUntil)
        .setParameter(11, invalidatedAt)
        .setParameter(12, invalidatedByUserId)
        .setParameter(13, invalidationReason)
        .setParameter(14, metadata)
        .setParameter(15, memoryId)
        .executeUpdate();
    entityManager.clear();
  }

  private void softDelete(UUID memoryId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE confirmed_memories SET deleted_at = ?1 WHERE id = ?2")
        .setParameter(1, Instant.parse("2026-07-05T09:00:00Z"))
        .setParameter(2, memoryId)
        .executeUpdate();
    entityManager.clear();
  }
}
