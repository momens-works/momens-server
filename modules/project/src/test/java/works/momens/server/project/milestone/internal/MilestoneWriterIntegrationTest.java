package works.momens.server.project.milestone.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
                true,
                "active",
                LocalDate.of(2026, 10, 1),
                true,
                "on_track",
                60,
                "요약",
                true));

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
                        milestone.getId(),
                        null,
                        null,
                        false,
                        null,
                        null,
                        false,
                        "unknown",
                        null,
                        null,
                        false)))
        .isInstanceOf(FieldValidationException.class);

    assertThatThrownBy(
            () ->
                milestoneWriter.update(
                    new UpdateMilestoneCommand(
                        milestone.getId(),
                        null,
                        null,
                        false,
                        null,
                        null,
                        false,
                        null,
                        101,
                        null,
                        false)))
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
                        milestone.getId(),
                        null,
                        null,
                        false,
                        "unknown",
                        null,
                        false,
                        null,
                        null,
                        null,
                        false)))
        .isInstanceOf(FieldValidationException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void clearsExplicitOptionalFieldsAndAcceptsZeroProgress(String clearedText) {
    Milestone milestone = savePopulatedMilestone("writer-clear");
    Instant updatedAtBefore = milestone.getUpdatedAt();

    MilestoneDetail detail =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                milestone.getId(),
                null,
                clearedText,
                true,
                null,
                null,
                true,
                null,
                0,
                clearedText,
                true));

    assertThat(detail.description()).isNull();
    assertThat(detail.summary()).isNull();
    assertThat(detail.targetDate()).isNull();
    assertThat(detail.progress()).isZero();
    assertThat(detail.updatedAt()).isAfter(updatedAtBefore);
    entityManager.clear();
    Milestone stored = milestoneRepository.findById(milestone.getId()).orElseThrow();
    assertThat(stored.getDescription()).isNull();
    assertThat(stored.getSummary()).isNull();
    assertThat(stored.getTargetDate()).isNull();
    assertThat(stored.getProgress()).isZero();
  }

  @Test
  void omittedFieldsAndIdenticalValuesKeepUpdatedAt() {
    Milestone milestone = savePopulatedMilestone("writer-noop");
    Instant updatedAtBefore = milestone.getUpdatedAt();
    MilestoneDetail omitted =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                milestone.getId(), null, null, false, null, null, false, null, null, null, false));
    MilestoneDetail identical =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                milestone.getId(),
                milestone.getName(),
                "설명",
                true,
                milestone.getStatus(),
                milestone.getTargetDate(),
                true,
                milestone.getHealthStatus(),
                60,
                "요약",
                true));

    assertThat(omitted.description()).isEqualTo("설명");
    assertThat(omitted.summary()).isEqualTo("요약");
    assertThat(omitted.targetDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(omitted.updatedAt()).isEqualTo(updatedAtBefore);
    assertThat(identical).isEqualTo(omitted);
  }

  @Test
  void clearingOneFieldPreservesOtherOptionalFields() {
    Milestone milestone = savePopulatedMilestone("writer-partial");
    MilestoneDetail detail =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                milestone.getId(), null, null, true, null, null, false, null, null, null, false));
    assertThat(detail.description()).isNull();
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.targetDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(detail.progress()).isEqualTo(60);
  }

  private Milestone savePopulatedMilestone(String slug) {
    Fixture fixture = newFixture(slug);
    return milestoneRepository.saveAndFlush(
        Milestone.builder()
            .projectId(fixture.projectId())
            .name("마일스톤")
            .description("설명")
            .summary("요약")
            .targetDate(LocalDate.of(2026, 10, 1))
            .progress(60)
            .build());
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
