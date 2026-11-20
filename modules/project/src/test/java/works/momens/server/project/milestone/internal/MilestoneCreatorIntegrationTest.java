package works.momens.server.project.milestone.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 마일스톤 생성 public API의 동작을 검증합니다.
 *
 * <p>PostgreSQL(Testcontainers) 환경에서 소유자를 결정하는 기준을 검증합니다. 요청에서 소유자를 지정한 경우와 지정하지 않은 경우를 각각 확인하며,
 * 소유자를 지정하지 않은 경우에는 프로젝트 소유자가 현재 워크스페이스 멤버가 아니어도 요청자가 소유자로 저장되는지 함께 검증합니다.
 *
 * <p>워크스페이스 멤버십 조회는 다른 모듈의 public API이므로 {@code @MockitoBean}으로 대체하고 필요한 반환값을 지정합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MilestoneWriterImpl.class, MilestoneOwnerMembershipChecker.class})
class MilestoneCreatorIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MilestoneWriter milestoneWriter;
  @Autowired private MilestoneRepository milestoneRepository;
  @Autowired private MilestoneOwnerRepository milestoneOwnerRepository;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  void savesRequestedOwnersAndLegacyDefaults() {
    Fixture fixture = newProject("momens-milestone");
    givenMembers(fixture.workspaceId(), fixture.requesterId());

    MilestoneDetail detail =
        milestoneWriter.create(command(fixture, List.of(fixture.requesterId())));

    assertThat(detail.status()).isEqualTo("planned");
    assertThat(detail.healthStatus()).isEqualTo("planned");
    assertThat(detail.progress()).isZero();
    assertThat(detail.ownerUserIds()).containsExactly(fixture.requesterId());

    entityManager.flush();
    entityManager.clear();
    assertThat(milestoneRepository.findById(detail.id())).isPresent();
    assertThat(
            milestoneOwnerRepository.findByMilestoneIdInOrderByCreatedAtAscOwnerUserIdAsc(
                List.of(detail.id())))
        .extracting(MilestoneOwner::getOwnerUserId)
        .containsExactly(fixture.requesterId());
  }

  @Test
  void ownsTheRequesterWhenOwnersAreOmitted() {
    Fixture fixture = newProject("momens-omitted-owners");
    UUID formerMember = ProjectSeedSql.insertUser(entityManager, "formermember@momens.works");
    ProjectSeedSql.insertProjectOwner(entityManager, fixture.projectId(), formerMember);
    givenMembers(fixture.workspaceId(), fixture.requesterId());

    MilestoneDetail detail = milestoneWriter.create(command(fixture, null));

    assertThat(detail.ownerUserIds()).containsExactly(fixture.requesterId());
  }

  private Fixture newProject(String slug) {
    UUID requesterId = ProjectSeedSql.insertUser(entityManager, slug + "@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, slug);
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, requesterId);
    return new Fixture(workspaceId, projectId, requesterId);
  }

  private void givenMembers(UUID workspaceId, UUID... userIds) {
    given(workspaceMembershipReader.listMemberUserIds(workspaceId)).willReturn(List.of(userIds));
  }

  private static CreateMilestoneCommand command(Fixture fixture, List<UUID> ownerUserIds) {
    return new CreateMilestoneCommand(
        fixture.projectId(),
        fixture.workspaceId(),
        fixture.requesterId(),
        "태스크 목록 조회 API 마감",
        null,
        null,
        null,
        null,
        null,
        null,
        ownerUserIds);
  }

  private record Fixture(UUID workspaceId, UUID projectId, UUID requesterId) {}
}
