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
import works.momens.server.project.core.ProjectOwnerReader;
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneCreator;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 마일스톤 생성 public API의 동작을 검증합니다.
 *
 * <p>PostgreSQL(Testcontainers) 환경에서 소유자 기본값이 결정되는 우선순위를 확인합니다. 요청에서 소유자를 지정한 경우, 소유자를 지정하지 않아 프로젝트
 * 소유자가 적용되는 경우, 프로젝트 소유자도 없어 요청자가 적용되는 경우를 각각 검증합니다. 첫 번째 테스트에서는 마일스톤과 소유자 행이 실제로 저장되는지와 상태 기본값이
 * 적용되는지도 함께 확인합니다.
 *
 * <p>워크스페이스 멤버십 조회는 다른 모듈의 public API이므로 {@code @MockitoBean}으로 대체하고 반환값을 지정합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfig.class,
  MilestoneCreatorImpl.class,
  MilestoneOwnerMembershipChecker.class
})
class MilestoneCreatorIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MilestoneCreator milestoneCreator;
  @Autowired private MilestoneRepository milestoneRepository;
  @Autowired private MilestoneOwnerRepository milestoneOwnerRepository;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;
  @MockitoBean private ProjectOwnerReader projectOwnerReader;

  @Test
  void savesRequestedOwnersAndLegacyDefaults() {
    Fixture fixture = newProject("momens-milestone");
    givenMembers(fixture.workspaceId(), fixture.requesterId());

    MilestoneDetail detail =
        milestoneCreator.create(command(fixture, List.of(fixture.requesterId())));

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
  void fallsBackToProjectOwnersWhenOwnersAreOmitted() {
    Fixture fixture = newProject("momens-project-owners");
    UUID projectOwnerId = ProjectSeedSql.insertUser(entityManager, "projectowner@momens.works");
    given(projectOwnerReader.listOwnerUserIds(fixture.projectId()))
        .willReturn(List.of(projectOwnerId));
    givenMembers(fixture.workspaceId(), fixture.requesterId(), projectOwnerId);

    MilestoneDetail detail = milestoneCreator.create(command(fixture, null));

    assertThat(detail.ownerUserIds()).containsExactly(projectOwnerId);
  }

  @Test
  void fallsBackToRequesterWhenProjectHasNoOwners() {
    Fixture fixture = newProject("momens-no-owners");
    given(projectOwnerReader.listOwnerUserIds(fixture.projectId())).willReturn(List.of());
    givenMembers(fixture.workspaceId(), fixture.requesterId());

    MilestoneDetail detail = milestoneCreator.create(command(fixture, null));

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
