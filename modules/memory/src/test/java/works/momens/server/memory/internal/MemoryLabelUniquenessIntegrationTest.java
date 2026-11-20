package works.momens.server.memory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.memory.MemorySeedSql;

/**
 * local/test에서도 prod와 같은 중복 라벨을 거부해야 합니다.
 *
 * <p>조건 없는 UNIQUE도 NULL 라벨의 공존을 허용하므로, 행동 검사만으로는 부분 조건 누락을 잡지 못합니다. 시스템 카탈로그의 인덱스 정의도 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("memory 라벨 UNIQUE 제약 통합 테스트")
class MemoryLabelUniquenessIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("같은 워크스페이스에 같은 확정 메모리 라벨을 두 번 넣을 수 없다")
  void rejectsDuplicateMemoryLabelInSameWorkspace() {
    UUID workspaceId = MemorySeedSql.insertWorkspace(entityManager);
    insertMemoryWithLabel(workspaceId, "MEM-0001");

    assertThatThrownBy(() -> insertMemoryWithLabel(workspaceId, "MEM-0001"))
        .hasMessageContaining("idx_confirmed_memories_workspace_label");
  }

  @Test
  @DisplayName("같은 워크스페이스에 같은 후보 라벨을 두 번 넣을 수 없다")
  void rejectsDuplicateCandidateLabelInSameWorkspace() {
    UUID workspaceId = MemorySeedSql.insertWorkspace(entityManager);
    insertCandidateWithLabel(workspaceId, "SUG-0001");

    assertThatThrownBy(() -> insertCandidateWithLabel(workspaceId, "SUG-0001"))
        .hasMessageContaining("idx_memory_candidates_workspace_label");
  }

  @Test
  @DisplayName("워크스페이스가 다르면 같은 라벨을 쓸 수 있다")
  void allowsSameLabelInDifferentWorkspaces() {
    UUID first = MemorySeedSql.insertWorkspace(entityManager);
    UUID second = MemorySeedSql.insertWorkspace(entityManager);

    insertMemoryWithLabel(first, "MEM-0001");
    insertMemoryWithLabel(second, "MEM-0001");

    assertThat(memoryCount("MEM-0001")).isEqualTo(2);
  }

  @Test
  @DisplayName("라벨이 없는 행은 부분 조건 밖이라 여러 개 공존한다")
  void allowsManyRowsWithoutLabel() {
    UUID workspaceId = MemorySeedSql.insertWorkspace(entityManager);

    insertMemoryWithLabel(workspaceId, null);
    insertMemoryWithLabel(workspaceId, null);

    assertThat(memoryCount(null)).isEqualTo(2);
  }

  @Test
  @DisplayName("두 인덱스는 레거시와 같은 부분 조건을 가진 UNIQUE 인덱스다")
  void indexesAreUniqueAndPartialOnLabelPresence() {
    assertThat(indexDefinition("idx_confirmed_memories_workspace_label"))
        .contains("CREATE UNIQUE INDEX")
        .contains("ON public.confirmed_memories USING btree (workspace_id, label)")
        .contains("WHERE (label IS NOT NULL)");
    assertThat(indexDefinition("idx_memory_candidates_workspace_label"))
        .contains("CREATE UNIQUE INDEX")
        .contains("ON public.memory_candidates USING btree (workspace_id, label)")
        .contains("WHERE (label IS NOT NULL)");
  }

  private String indexDefinition(String indexName) {
    return (String)
        entityManager
            .getEntityManager()
            .createNativeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = ?1")
            .setParameter(1, indexName)
            .getSingleResult();
  }

  private void insertMemoryWithLabel(UUID workspaceId, String label) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO confirmed_memories (id, workspace_id, label, memory_type, title)"
                + " VALUES (?1, ?2, ?3, 'DECISION', ?4)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, workspaceId)
        .setParameter(3, label)
        .setParameter(4, "결제 재시도는 3회로 고정한다")
        .executeUpdate();
    entityManager.flush();
  }

  private void insertCandidateWithLabel(UUID workspaceId, String label) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO memory_candidates (id, workspace_id, label, candidate_type, title)"
                + " VALUES (?1, ?2, ?3, 'DECISION', ?4)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, workspaceId)
        .setParameter(3, label)
        .setParameter(4, "결제 재시도 정책 후보")
        .executeUpdate();
    entityManager.flush();
  }

  private long memoryCount(String label) {
    String sql =
        label == null
            ? "SELECT count(*) FROM confirmed_memories WHERE label IS NULL"
            : "SELECT count(*) FROM confirmed_memories WHERE label = ?1";
    var query = entityManager.getEntityManager().createNativeQuery(sql);
    if (label != null) {
      query.setParameter(1, label);
    }
    return ((Number) query.getSingleResult()).longValue();
  }
}
