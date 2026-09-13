package works.momens.server.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.context.EntityRelationCommand;
import works.momens.server.context.EntityRelationWriter;
import works.momens.server.context.EntityType;
import works.momens.server.context.RelationType;
import works.momens.server.workspace.WorkspaceSeedSql;

/**
 * 엔티티 연결 쓰기 public API를 검증합니다.
 *
 * <p>레거시 {@code relation/repository.go}의 {@code LinkWithType}과 같은 upsert 시맨틱인지 봅니다. {@code
 * entity_relations}에는 UNIQUE 제약이 없어 조건 없이 INSERT 하면 중복 행이 조용히 쌓이고, 그 상태는 스냅샷 응답과 retrieval
 * projection에 그대로 드러납니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EntityRelationWriterImpl.class)
@Transactional(propagation = Propagation.REQUIRED)
@DisplayName("EntityRelationWriter 통합 테스트")
class EntityRelationWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private EntityRelationWriter entityRelationWriter;
  @Autowired private TestEntityManager entityManager;

  private final UUID fromId = UUID.randomUUID();
  private final UUID toId = UUID.randomUUID();
  private UUID workspaceId;

  @BeforeEach
  void insertWorkspace() {
    workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "ws-" + UUID.randomUUID());
  }

  @Test
  @DisplayName("연결이 없으면 새로 만든다")
  void createsRelationWhenAbsent() {
    UUID relationId = entityRelationWriter.link(command());

    assertThat(relationIds()).containsExactly(relationId);
    assertThat(deletedAt(relationId)).isNull();
  }

  @Test
  @DisplayName("같은 연결이 이미 살아 있으면 중복 행을 만들지 않는다")
  void reusesLiveRelation() {
    UUID first = entityRelationWriter.link(command());

    UUID second = entityRelationWriter.link(command());

    assertThat(second).isEqualTo(first);
    assertThat(relationIds()).containsExactly(first);
  }

  @Test
  @DisplayName("소프트 삭제된 연결은 새로 만들지 않고 되살린다")
  void revivesSoftDeletedRelation() {
    UUID first = entityRelationWriter.link(command());
    softDelete(first);

    UUID second = entityRelationWriter.link(command());

    assertThat(second).isEqualTo(first);
    assertThat(relationIds()).containsExactly(first);
    assertThat(deletedAt(first)).isNull();
  }

  @Test
  @DisplayName("연결 종류가 다르면 별개의 연결로 만든다")
  void separatesDifferentRelationTypes() {
    UUID resolves = entityRelationWriter.link(command());

    UUID references =
        entityRelationWriter.link(
            new EntityRelationCommand(
                workspaceId,
                EntityType.MEMORY,
                fromId,
                RelationType.LINKED_TO,
                EntityType.MEMORY,
                toId));

    assertThat(references).isNotEqualTo(resolves);
    assertThat(relationIds()).containsExactlyInAnyOrder(resolves, references);
  }

  @Test
  @DisplayName("삭제되지 않은 연결을 지우면 행은 남기고 삭제 시각만 채운다")
  void softDeletesLiveRelation() {
    UUID relationId = entityRelationWriter.link(command());

    boolean removed = entityRelationWriter.unlink(command());

    assertThat(removed).isTrue();
    assertThat(relationIds()).containsExactly(relationId);
    assertThat(deletedAt(relationId)).isNotNull();
  }

  @Test
  @DisplayName("지울 연결이 없으면 아무것도 지우지 않았다고 알린다")
  void reportsNothingRemovedWhenRelationAbsent() {
    boolean removed = entityRelationWriter.unlink(command());

    assertThat(removed).isFalse();
    assertThat(relationIds()).isEmpty();
  }

  @Test
  @DisplayName("이미 지운 연결을 다시 지우면 아무것도 지우지 않았다고 알린다")
  void reportsNothingRemovedWhenRelationAlreadySoftDeleted() {
    UUID relationId = entityRelationWriter.link(command());
    entityRelationWriter.unlink(command());

    boolean removed = entityRelationWriter.unlink(command());

    assertThat(removed).isFalse();
    assertThat(relationIds()).containsExactly(relationId);
  }

  private EntityRelationCommand command() {
    return new EntityRelationCommand(
        workspaceId, EntityType.MEMORY, fromId, RelationType.RESOLVES, EntityType.MEMORY, toId);
  }

  private List<UUID> relationIds() {
    @SuppressWarnings("unchecked")
    List<UUID> ids =
        entityManager
            .getEntityManager()
            .createNativeQuery(
                "SELECT id FROM entity_relations WHERE workspace_id = ?1 ORDER BY created_at")
            .setParameter(1, workspaceId)
            .getResultList();
    return ids;
  }

  private Object deletedAt(UUID relationId) {
    return entityManager
        .getEntityManager()
        .createNativeQuery("SELECT deleted_at FROM entity_relations WHERE id = ?1")
        .setParameter(1, relationId)
        .getSingleResult();
  }

  private void softDelete(UUID relationId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE entity_relations SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, relationId)
        .executeUpdate();
  }
}
