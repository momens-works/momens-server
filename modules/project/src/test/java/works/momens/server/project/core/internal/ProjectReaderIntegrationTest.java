package works.momens.server.project.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.core.ProjectSnapshot;
import works.momens.server.project.task.TaskReader;

/** 접근 범위는 호출자가 확정하므로 이 테스트는 workspace id 목록을 직접 전달해 프로젝트 조회를 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ProjectReaderImpl.class})
class ProjectReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @MockitoBean private TaskReader taskReader;

  @Autowired private ProjectReader projectReader;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void listByWorkspaceIdsReturnsLiveProjectsOfGivenWorkspacesNewestFirst() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "reader-owner@momens.works");
    UUID firstWorkspace = ProjectSeedSql.insertWorkspace(entityManager, "reader-first");
    UUID secondWorkspace = ProjectSeedSql.insertWorkspace(entityManager, "reader-second");
    UUID otherWorkspace = ProjectSeedSql.insertWorkspace(entityManager, "reader-others");

    UUID older = saveProject(firstWorkspace, ownerId, "older").getId();
    UUID newer = saveProject(secondWorkspace, ownerId, "newer").getId();
    UUID deleted = saveProject(firstWorkspace, ownerId, "deleted").getId();
    softDelete(deleted);
    saveProject(otherWorkspace, ownerId, "not-mine");

    List<ProjectSnapshot> snapshots =
        projectReader.listByWorkspaceIds(List.of(firstWorkspace, secondWorkspace));

    assertThat(snapshots).extracting(ProjectSnapshot::id).containsExactly(newer, older);
  }

  @Test
  void listByWorkspaceIdsIsEmptyForEmptyWorkspaceIds() {
    assertThat(projectReader.listByWorkspaceIds(List.of())).isEmpty();
  }

  @Test
  void workspaceIdOfResolvesLiveProjectOnly() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "resolve-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "resolve");
    UUID live = saveProject(workspaceId, ownerId, "live").getId();
    UUID deleted = saveProject(workspaceId, ownerId, "gone").getId();
    softDelete(deleted);

    assertThat(projectReader.workspaceIdOf(live)).contains(workspaceId);
    assertThat(projectReader.workspaceIdOf(deleted)).isEmpty();
    assertThat(projectReader.workspaceIdOf(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findSnapshotMapsMobileReadFields() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "snapshot-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "snapshot");
    UUID projectId =
        projectRepository
            .saveAndFlush(
                Project.builder()
                    .workspaceId(workspaceId)
                    .name("Q2 Activation Readiness")
                    .ownerId(ownerId)
                    .targetDate(LocalDate.of(2026, 6, 30))
                    .summary("요약")
                    .build())
            .getId();

    assertThat(projectReader.findSnapshot(projectId))
        .contains(
            new ProjectSnapshot(
                projectId,
                workspaceId,
                "Q2 Activation Readiness",
                LocalDate.of(2026, 6, 30),
                "요약"));
  }

  private Project saveProject(UUID workspaceId, UUID ownerId, String name) {
    return projectRepository.saveAndFlush(
        Project.builder().workspaceId(workspaceId).name(name).ownerId(ownerId).build());
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
