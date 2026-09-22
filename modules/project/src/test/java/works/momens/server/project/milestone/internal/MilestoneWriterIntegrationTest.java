package works.momens.server.project.milestone.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.ProjectOwnerReader;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MilestoneWriterImpl.class, MilestoneOwnerMembershipChecker.class})
class MilestoneWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MilestoneWriter milestoneWriter;
  @Autowired private MilestoneRepository milestoneRepository;
  @Autowired private MilestoneOwnerRepository milestoneOwnerRepository;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private ProjectOwnerReader projectOwnerReader;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  void updatesMcpFieldsAndKeepsExistingOwners() {
    Fixture fixture = newFixture("writer-update");
    Milestone milestone = saveMilestone(fixture.projectId(), "기존 마일스톤");
    Instant updatedAtBefore = milestone.getUpdatedAt();
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "writer-owner@momens.works");
    milestoneOwnerRepository.saveAndFlush(MilestoneOwner.of(milestone.getId(), ownerId));

    MilestoneDetail detail =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                milestone.getId(),
                "새 마일스톤",
                "설명",
                "active",
                LocalDate.of(2026, 10, 1),
                "on_track",
                60,
                "요약"));

    assertThat(detail.name()).isEqualTo("새 마일스톤");
    assertThat(detail.description()).isEqualTo("설명");
    assertThat(detail.status()).isEqualTo("active");
    assertThat(detail.targetDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(detail.healthStatus()).isEqualTo("on_track");
    assertThat(detail.progress()).isEqualTo(60);
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.ownerUserIds()).containsExactly(ownerId);
    assertThat(detail.updatedAt()).isAfter(updatedAtBefore);
  }

  @Test
  void rejectsInvalidHealthStatusAndProgress() {
    Fixture fixture = newFixture("writer-validation");
    Milestone milestone = saveMilestone(fixture.projectId(), "마일스톤");

    assertThatThrownBy(
            () ->
                milestoneWriter.update(
                    new UpdateMilestoneCommand(
                        milestone.getId(), null, null, null, null, "unknown", null, null)))
        .isInstanceOf(FieldValidationException.class);

    assertThatThrownBy(
            () ->
                milestoneWriter.update(
                    new UpdateMilestoneCommand(
                        milestone.getId(), null, null, null, null, null, 101, null)))
        .isInstanceOf(FieldValidationException.class);
  }

  @Test
  void rejectsInvalidStatus() {
    Fixture fixture = newFixture("writer-status-validation");
    Milestone milestone = saveMilestone(fixture.projectId(), "마일스톤");

    assertThatThrownBy(
            () ->
                milestoneWriter.update(
                    new UpdateMilestoneCommand(
                        milestone.getId(), null, null, "unknown", null, null, null, null)))
        .isInstanceOf(FieldValidationException.class);
  }

  @Test
  void softDeletesMilestone() {
    Fixture fixture = newFixture("writer-delete");
    Milestone milestone = saveMilestone(fixture.projectId(), "삭제할 마일스톤");

    milestoneWriter.delete(milestone.getId());

    entityManager.flush();
    entityManager.clear();
    assertThat(milestoneRepository.findByIdAndDeletedAtIsNull(milestone.getId())).isEmpty();
    assertThat(milestoneRepository.findById(milestone.getId()))
        .get()
        .extracting(Milestone::getDeletedAt)
        .isNotNull();
  }

  private Fixture newFixture(String slug) {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, slug + "@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, slug);
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    return new Fixture(projectId);
  }

  private Milestone saveMilestone(UUID projectId, String name) {
    Milestone milestone = Milestone.builder().projectId(projectId).name(name).build();
    return milestoneRepository.saveAndFlush(milestone);
  }

  private record Fixture(UUID projectId) {}
}
