package works.momens.server.project.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;

/** 생성 경로와 독립적으로 기존 공유 DB 행의 소유자 정렬·폴백·삭제 필터를 검증하도록 SQL로 시드합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ProjectDetailReaderImpl.class})
class ProjectDetailReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private ProjectDetailReader projectDetailReader;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void listDetailsReturnsStoredWebFields() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "detail-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "detail");
    UUID projectId =
        projectRepository
            .saveAndFlush(
                Project.builder()
                    .workspaceId(workspaceId)
                    .name("Q2 Activation Readiness")
                    .description("설명")
                    .ownerId(ownerId)
                    .targetDate(LocalDate.of(2026, 6, 30))
                    .summary("요약")
                    .build())
            .getId();
    setWebFields(projectId, "PRJ-0001", "at_risk", 3, 5, Instant.parse("2026-06-01T09:00:00Z"));
    setMetadata(projectId, "{\"pinned\": true}");

    ProjectDetail detail = onlyDetailOf(workspaceId);

    assertThat(detail.id()).isEqualTo(projectId);
    assertThat(detail.workspaceId()).isEqualTo(workspaceId);
    assertThat(detail.label()).isEqualTo("PRJ-0001");
    assertThat(detail.name()).isEqualTo("Q2 Activation Readiness");
    assertThat(detail.description()).isEqualTo("설명");
    assertThat(detail.status()).isEqualTo("active");
    assertThat(detail.ownerId()).isEqualTo(ownerId);
    assertThat(detail.targetDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(detail.healthStatus()).isEqualTo("at_risk");
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.unresolvedCount()).isEqualTo(3);
    assertThat(detail.vocSignalCount()).isEqualTo(5);
    assertThat(detail.lastContextAt()).isEqualTo(Instant.parse("2026-06-01T09:00:00Z"));
    assertThat(detail.metadata()).isEqualTo(Map.of("pinned", true));
    assertThat(detail.createdAt()).isNotNull();
    assertThat(detail.updatedAt()).isNotNull();
  }

  @Test
  void listDetailsLeavesOptionalFieldsEmptyWhenNotStored() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "sparse-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "sparse");
    saveProject(workspaceId, ownerId, "이름만 있는 프로젝트");

    ProjectDetail detail = onlyDetailOf(workspaceId);

    assertThat(detail.label()).isNull();
    assertThat(detail.description()).isNull();
    assertThat(detail.targetDate()).isNull();
    assertThat(detail.summary()).isNull();
    assertThat(detail.lastContextAt()).isNull();
    assertThat(detail.metadata()).isNull();
    assertThat(detail.healthStatus()).isEqualTo("open");
    assertThat(detail.unresolvedCount()).isZero();
    assertThat(detail.vocSignalCount()).isZero();
  }

  @Test
  void softDeletedProjectIsGoneFromTheList() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "gone-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "gone");
    UUID deleted = saveProject(workspaceId, ownerId, "삭제됨").getId();
    softDelete(deleted);

    assertThat(projectDetailReader.listDetailsByWorkspaceId(workspaceId)).isEmpty();
  }

  @Test
  void ownerUserIdsFollowCreatedAtThenOwnerUserId() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "owners-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "owners");
    UUID projectId = saveProject(workspaceId, ownerId, "소유자 여럿").getId();

    UUID late = ProjectSeedSql.insertUser(entityManager, "late@momens.works");
    // 같은 created_at 두 건은 owner_user_id로 tie-break 되므로, 큰 쪽과 작은 쪽을 함께 넣는다.
    UUID tieHigh = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
    UUID tieLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
    insertUserWithId(tieHigh, "tie-high@momens.works");
    insertUserWithId(tieLow, "tie-low@momens.works");

    ProjectSeedSql.insertProjectOwner(
        entityManager, projectId, tieHigh, Instant.parse("2026-06-01T00:00:00Z"));
    ProjectSeedSql.insertProjectOwner(
        entityManager, projectId, tieLow, Instant.parse("2026-06-01T00:00:00Z"));
    ProjectSeedSql.insertProjectOwner(
        entityManager, projectId, late, Instant.parse("2026-06-02T00:00:00Z"));

    assertThat(onlyDetailOf(workspaceId).ownerUserIds()).containsExactly(tieLow, tieHigh, late);
  }

  @Test
  void ownerUserIdsFallBackToOwnerIdWhenNoRows() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "fallback-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "fallback");
    saveProject(workspaceId, ownerId, "소유자 행 없음");

    assertThat(onlyDetailOf(workspaceId).ownerUserIds()).containsExactly(ownerId);
  }

  @Test
  void listDetailsReturnsLiveProjectsOfWorkspaceNewestFirstWithOwnOwners() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "list-owner@momens.works");
    UUID explicitOwner = ProjectSeedSql.insertUser(entityManager, "list-explicit@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "list");
    UUID otherWorkspace = ProjectSeedSql.insertWorkspace(entityManager, "list-other");

    UUID older = saveProject(workspaceId, ownerId, "먼저").getId();
    UUID newer = saveProject(workspaceId, ownerId, "나중").getId();
    // 감사 필드는 연속 저장에서 같은 값이 될 수 있고 정렬에 tie-break가 없으므로 명시적으로 벌린다.
    setCreatedAt(older, Instant.parse("2026-06-01T00:00:00Z"));
    setCreatedAt(newer, Instant.parse("2026-06-02T00:00:00Z"));
    UUID deleted = saveProject(workspaceId, ownerId, "삭제됨").getId();
    softDelete(deleted);
    saveProject(otherWorkspace, ownerId, "다른 워크스페이스");
    ProjectSeedSql.insertProjectOwner(
        entityManager, newer, explicitOwner, Instant.parse("2026-06-01T00:00:00Z"));

    List<ProjectDetail> details = projectDetailReader.listDetailsByWorkspaceId(workspaceId);

    assertThat(details).extracting(ProjectDetail::id).containsExactly(newer, older);
    // 소유자 행이 있는 project와 폴백하는 project가 한 목록에서 각자 값을 갖는다.
    assertThat(details.get(0).ownerUserIds()).containsExactly(explicitOwner);
    assertThat(details.get(1).ownerUserIds()).containsExactly(ownerId);
  }

  @Test
  void listDetailsIsEmptyForWorkspaceWithoutProjects() {
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "list-empty");

    assertThat(projectDetailReader.listDetailsByWorkspaceId(workspaceId)).isEmpty();
  }

  private ProjectDetail onlyDetailOf(UUID workspaceId) {
    List<ProjectDetail> details = projectDetailReader.listDetailsByWorkspaceId(workspaceId);
    assertThat(details).hasSize(1);
    return details.getFirst();
  }

  private Project saveProject(UUID workspaceId, UUID ownerId, String name) {
    return projectRepository.saveAndFlush(
        Project.builder().workspaceId(workspaceId).name(name).ownerId(ownerId).build());
  }

  private void insertUserWithId(UUID id, String email) {
    entityManager
        .getEntityManager()
        .createNativeQuery("INSERT INTO users (id, email, name) VALUES (?1, ?2, ?3)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "이름")
        .executeUpdate();
  }

  /** 목록 정렬을 결정적으로 만들기 위해 생성 시각을 지정합니다. 감사 필드라 앱에서 정할 수 없습니다. */
  private void setCreatedAt(UUID projectId, Instant createdAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE projects SET created_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, projectId)
        .executeUpdate();
    entityManager.clear();
  }

  private void setWebFields(
      UUID projectId,
      String label,
      String healthStatus,
      int unresolvedCount,
      int vocSignalCount,
      Instant lastContextAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE projects SET label = ?1, health_status = ?2, unresolved_count = ?3,"
                + " voc_signal_count = ?4, last_context_at = ?5 WHERE id = ?6")
        .setParameter(1, label)
        .setParameter(2, healthStatus)
        .setParameter(3, unresolvedCount)
        .setParameter(4, vocSignalCount)
        .setParameter(5, lastContextAt)
        .setParameter(6, projectId)
        .executeUpdate();
    entityManager.clear();
  }

  private void setMetadata(UUID projectId, String json) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE projects SET metadata = CAST(?1 AS jsonb) WHERE id = ?2")
        .setParameter(1, json)
        .setParameter(2, projectId)
        .executeUpdate();
    entityManager.clear();
  }

  /** 조회할 삭제 상태를 네이티브 SQL로 구성합니다. */
  private void softDelete(UUID projectId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE projects SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, projectId)
        .executeUpdate();
    entityManager.clear();
  }
}
