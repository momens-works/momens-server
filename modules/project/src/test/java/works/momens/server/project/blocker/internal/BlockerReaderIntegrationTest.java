package works.momens.server.project.blocker.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerReader;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, BlockerReaderImpl.class})
@DisplayName("BlockerReader 통합 테스트")
class BlockerReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private BlockerReader blockerReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("레거시가 저장한 필드와 nullable resolved_at을 그대로 반환한다")
  void listDetailsReturnsStoredFields() {
    Fixture fixture = new Fixture("stored");
    Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-08-02T00:00:00Z");
    Instant resolvedAt = Instant.parse("2026-08-03T00:00:00Z");
    UUID blockerId =
        fixture.insertTaskBlocker("배포가 막혔습니다", "resolved", createdAt, updatedAt, resolvedAt);

    BlockerDetail detail = fixture.onlyDetail();

    assertThat(detail.id()).isEqualTo(blockerId);
    assertThat(detail.workspaceId()).isEqualTo(fixture.workspaceId);
    assertThat(detail.description()).isEqualTo("배포가 막혔습니다");
    assertThat(detail.status()).isEqualTo("resolved");
    assertThat(detail.blockedEntityType()).isEqualTo("task");
    assertThat(detail.blockedEntityId()).isEqualTo(fixture.taskId);
    assertThat(detail.createdAt()).isEqualTo(createdAt);
    assertThat(detail.updatedAt()).isEqualTo(updatedAt);
    assertThat(detail.resolvedAt()).isEqualTo(resolvedAt);
  }

  @Test
  @DisplayName("active와 resolved blocker를 모두 생성 시각 내림차순으로 반환한다")
  void listDetailsIncludesAllStatusesNewestFirst() {
    Fixture fixture = new Fixture("ordered");
    UUID older =
        fixture.insertTaskBlocker(
            "먼저", "active", Instant.parse("2026-08-01T00:00:00Z"), null, null);
    UUID newer =
        fixture.insertTaskBlocker(
            "나중",
            "resolved",
            Instant.parse("2026-08-02T00:00:00Z"),
            null,
            Instant.parse("2026-08-03T00:00:00Z"));

    List<BlockerDetail> details = fixture.details();

    assertThat(details).extracting(BlockerDetail::id).containsExactly(newer, older);
    assertThat(details.get(1).resolvedAt()).isNull();
  }

  @Test
  @DisplayName("다른 워크스페이스의 blocker는 포함하지 않는다")
  void listDetailsIsScopedToWorkspace() {
    Fixture fixture = new Fixture("inside");
    UUID inside = fixture.insertTaskBlocker("내부", "active", null, null, null);
    Fixture outside = new Fixture("outside");
    outside.insertTaskBlocker("외부", "active", null, null, null);

    assertThat(fixture.details()).extracting(BlockerDetail::id).containsExactly(inside);
  }

  @Test
  @DisplayName("blocker가 없는 워크스페이스는 빈 목록을 반환한다")
  void listDetailsIsEmptyForWorkspaceWithoutBlockers() {
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "blocker-empty");

    assertThat(blockerReader.listDetailsByWorkspaceId(workspaceId)).isEmpty();
  }

  private final class Fixture {

    private final UUID workspaceId;
    private final UUID taskId;

    private Fixture(String slug) {
      UUID ownerId = ProjectSeedSql.insertUser(entityManager, slug + "-owner@momens.works");
      this.workspaceId = ProjectSeedSql.insertWorkspace(entityManager, slug + "-blocker");
      UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
      this.taskId = insertTask(projectId, workspaceId, slug + " task");
    }

    private UUID insertTaskBlocker(
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {
      UUID id = UUID.randomUUID();
      entityManager
          .getEntityManager()
          .createNativeQuery(
              "INSERT INTO blockers"
                  + " (id, workspace_id, description, status, blocked_entity_type,"
                  + " blocked_entity_id, task_id, created_at, updated_at, resolved_at)"
                  + " VALUES (?1, ?2, ?3, ?4, 'task', ?5, ?5,"
                  + " COALESCE(CAST(?6 AS timestamptz), NOW()),"
                  + " COALESCE(CAST(?7 AS timestamptz), NOW()), ?8)")
          .setParameter(1, id)
          .setParameter(2, workspaceId)
          .setParameter(3, description)
          .setParameter(4, status)
          .setParameter(5, taskId)
          .setParameter(6, createdAt)
          .setParameter(7, updatedAt)
          .setParameter(8, resolvedAt)
          .executeUpdate();
      entityManager.clear();
      return id;
    }

    private List<BlockerDetail> details() {
      return blockerReader.listDetailsByWorkspaceId(workspaceId);
    }

    private BlockerDetail onlyDetail() {
      List<BlockerDetail> details = details();
      assertThat(details).hasSize(1);
      return details.getFirst();
    }
  }

  private UUID insertTask(UUID projectId, UUID workspaceId, String title) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO tasks (id, workspace_id, project_id, title) VALUES (?1, ?2, ?3, ?4)")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, projectId)
        .setParameter(4, title)
        .executeUpdate();
    return id;
  }
}
