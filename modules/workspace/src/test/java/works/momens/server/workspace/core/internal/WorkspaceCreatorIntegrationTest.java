package works.momens.server.workspace.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceCreator;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.membership.AddMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 생성 public API가 레거시 {@code workspace.Create}와 동일하게 동작하는지 PostgreSQL 환경에서 검증합니다.
 *
 * <p>slug 규칙은 {@link WorkspaceSlugPolicyTest}에서 검증하므로 해당 테스트에서는 DB 조회가 필요한 경로만 확인합니다. 이름으로 생성한
 * slug가 이미 사용 중일 때 다음 후보를 찾는 동작과 직접 지정한 slug를 거부하는 동작을 검증합니다.
 *
 * <p>멤버십 저장은 다른 하위 도메인의 public API이므로 {@code @MockitoBean}으로 교체하고 호출 인자만 확인합니다. 멤버 행이 DB에 저장되는지는
 * app 레벨 통합 테스트에서 검증합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, WorkspaceCreatorImpl.class})
class WorkspaceCreatorIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID REQUESTER_ID = UUID.randomUUID();

  @Autowired private WorkspaceCreator workspaceCreator;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipWriter workspaceMembershipWriter;

  @Test
  @DisplayName("slug를 전달하지 않으면 이름으로 생성하고 요청자를 owner로 등록한다")
  void derivesSlugFromNameAndRegistersRequesterAsOwner() {
    WorkspaceDetail detail =
        workspaceCreator.create(
            new CreateWorkspaceCommand(REQUESTER_ID, "Momens Team", "제품팀 워크스페이스", null));

    assertThat(detail.slug()).isEqualTo("momens-team");
    assertThat(detail.name()).isEqualTo("Momens Team");
    assertThat(detail.description()).isEqualTo("제품팀 워크스페이스");
    assertThat(detail.createdAt()).isNotNull();
    verify(workspaceMembershipWriter)
        .addIfAbsent(new AddMembershipCommand(detail.id(), REQUESTER_ID, WorkspaceRole.OWNER));
  }

  @Test
  @DisplayName("이름으로 생성한 slug가 이미 사용 중이면 뒤에 2를 붙인다")
  void appendsSuffixWhenDerivedSlugIsTaken() {
    WorkspaceSeedSql.insertWorkspace(entityManager, "momens-team");
    entityManager.flush();
    entityManager.clear();

    WorkspaceDetail detail =
        workspaceCreator.create(new CreateWorkspaceCommand(REQUESTER_ID, "Momens Team", "", null));

    assertThat(detail.slug()).isEqualTo("momens-team-2");
    assertThat(detail.description()).isNull();
  }

  @Test
  @DisplayName("이름이 비어 있거나 직접 지정한 slug가 규칙에 맞지 않으면 저장하지 않는다")
  void rejectsInvalidRequests() {
    WorkspaceSeedSql.insertWorkspace(entityManager, "momens-taken");
    entityManager.flush();
    entityManager.clear();

    assertThatThrownBy(
            () ->
                workspaceCreator.create(
                    new CreateWorkspaceCommand(REQUESTER_ID, "", null, "momens-free")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                workspaceCreator.create(
                    new CreateWorkspaceCommand(REQUESTER_ID, "모먼스", null, "Momens")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", WorkspaceErrorCode.WORKSPACE_INVALID_SLUG);
    assertThatThrownBy(
            () ->
                workspaceCreator.create(
                    new CreateWorkspaceCommand(REQUESTER_ID, "모먼스", null, "settings")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", WorkspaceErrorCode.WORKSPACE_RESERVED_SLUG);
    assertThatThrownBy(
            () ->
                workspaceCreator.create(
                    new CreateWorkspaceCommand(REQUESTER_ID, "모먼스", null, "momens-taken")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", WorkspaceErrorCode.WORKSPACE_SLUG_ALREADY_EXISTS);
    verify(workspaceMembershipWriter, org.mockito.Mockito.never()).addIfAbsent(any());
  }
}
