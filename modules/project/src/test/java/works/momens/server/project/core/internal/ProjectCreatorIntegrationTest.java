package works.momens.server.project.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.CreateProjectCommand;
import works.momens.server.project.core.ProjectCreator;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 프로젝트 생성 public API의 동작을 검증합니다.
 *
 * <p>PostgreSQL(Testcontainers) 환경에서 세 가지를 확인합니다. 발급된 라벨과 요청값이 실제로 저장되는지, 소유자를 지정하지 않았을 때 레거시 기본값과
 * 요청자가 소유자로 적용되는지, 동일한 사용자를 소유자로 두 번 전달한 요청이 거부되는지 검증합니다.
 *
 * <p>라벨 발급과 워크스페이스 멤버십 조회는 다른 모듈의 public API이므로 {@code @MockitoBean}으로 대체하고 반환값을 지정합니다. 라벨 번호가
 * 워크스페이스별로 독립적으로 증가하는지는 MOM-0907의 라벨 발급기 테스트에서 이미 검증합니다. 이 테스트에서는 발급된 라벨이 프로젝트 행에 저장되는지만 확인합니다.
 *
 * <p>{@code users}와 {@code workspaces}는 다른 모듈이 소유한 테이블이므로, FK 참조에 필요한 행만 네이티브 SQL로 생성합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ProjectCreatorImpl.class, ProjectOwnerMembershipChecker.class})
class ProjectCreatorIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private ProjectCreator projectCreator;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private ProjectOwnerRepository projectOwnerRepository;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private LabelAllocator labelAllocator;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  void savesAllocatedLabelRequestedValuesAndOwners() {
    UUID requesterId = ProjectSeedSql.insertUser(entityManager, "requester@momens.works");
    UUID memberId = ProjectSeedSql.insertUser(entityManager, "member@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "momens-create");
    givenMembers(workspaceId, requesterId, memberId);
    given(labelAllocator.allocateProjectLabel(workspaceId)).willReturn("PRJ-0007");

    ProjectDetail detail =
        projectCreator.create(
            new CreateProjectCommand(
                workspaceId,
                requesterId,
                "1차 스프린트",
                "",
                LocalDate.of(2026, 9, 30),
                "at_risk",
                42,
                "이번 스프린트 요약",
                3,
                1,
                Instant.parse("2026-08-20T00:00:00Z"),
                List.of(memberId, requesterId),
                Map.of("seeded", true)));

    assertThat(detail.label()).isEqualTo("PRJ-0007");
    assertThat(detail.ownerId()).isEqualTo(memberId);
    assertThat(detail.ownerUserIds()).containsExactly(memberId, requesterId);
    assertThat(detail.description()).isNull();

    entityManager.flush();
    entityManager.clear();
    Project saved = projectRepository.findById(detail.id()).orElseThrow();
    assertThat(saved.getLabel()).isEqualTo("PRJ-0007");
    assertThat(saved.getHealthStatus()).isEqualTo("at_risk");
    assertThat(saved.getProgress()).isEqualTo(42);
    assertThat(saved.getUnresolvedCount()).isEqualTo(3);
    assertThat(saved.getVocSignalCount()).isEqualTo(1);
    assertThat(saved.getMetadata()).containsEntry("seeded", true);
    assertThat(
            projectOwnerRepository.findByProjectIdInOrderByCreatedAtAscOwnerUserIdAsc(
                List.of(detail.id())))
        .extracting(ProjectOwner::getOwnerUserId)
        .containsExactlyInAnyOrder(memberId, requesterId);
  }

  @Test
  void appliesLegacyDefaultsAndOwnsTheRequesterWhenOwnersAreOmitted() {
    UUID requesterId = ProjectSeedSql.insertUser(entityManager, "solo@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "momens-default");
    givenMembers(workspaceId, requesterId);
    given(labelAllocator.allocateProjectLabel(workspaceId)).willReturn("PRJ-0001");

    ProjectDetail detail = projectCreator.create(command(workspaceId, requesterId, null));

    assertThat(detail.ownerId()).isEqualTo(requesterId);
    assertThat(detail.ownerUserIds()).containsExactly(requesterId);
    assertThat(detail.status()).isEqualTo("active");
    assertThat(detail.healthStatus()).isEqualTo("open");
    assertThat(detail.unresolvedCount()).isZero();
    assertThat(detail.vocSignalCount()).isZero();

    entityManager.clear();
    assertThat(projectRepository.findById(detail.id()).orElseThrow().getProgress()).isZero();
  }

  @Test
  void rejectsDuplicateOwnerUserIds() {
    UUID requesterId = ProjectSeedSql.insertUser(entityManager, "dup@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "momens-duplicate");
    givenMembers(workspaceId, requesterId);

    assertThatThrownBy(
            () ->
                projectCreator.create(
                    command(workspaceId, requesterId, List.of(requesterId, requesterId))))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  private void givenMembers(UUID workspaceId, UUID... userIds) {
    given(workspaceMembershipReader.listMemberUserIds(workspaceId)).willReturn(List.of(userIds));
  }

  private static CreateProjectCommand command(
      UUID workspaceId, UUID requesterId, List<UUID> ownerUserIds) {
    return new CreateProjectCommand(
        workspaceId,
        requesterId,
        "1차 스프린트",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        ownerUserIds,
        null);
  }
}
