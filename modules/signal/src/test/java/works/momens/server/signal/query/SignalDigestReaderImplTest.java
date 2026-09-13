package works.momens.server.signal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
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
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalDigestReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 시그널 요약 문단 조회 서비스 검증.
 *
 * <p>접근 검사(project와 workspace public API는 mock)와 범위 조회를 실제 PostgreSQL Testcontainers로 확인합니다. 범위 필터와
 * 정렬 자체는 {@code SignalDigestRepositoryIntegrationTest}가 봅니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SignalDigestReaderImpl.class)
class SignalDigestReaderImplTest extends AbstractPostgresIntegrationTest {

  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final Instant FROM = Instant.parse("2026-07-09T15:00:00Z");
  private static final Instant TO_EXCLUSIVE = Instant.parse("2026-07-10T15:00:00Z");

  @Autowired private SignalDigestReader signalDigestReader;
  @Autowired private TestEntityManager entityManager;
  @MockitoBean private ProjectReader projectReader;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  @DisplayName("범위에 있는 문단을 반환한다")
  void returnsDigestInRange() {
    allowMember();
    insertDigest("오늘은 회원가입 권한 요청 이탈이 주요 신호로 올라왔습니다.", FROM);

    assertThat(signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, FROM, TO_EXCLUSIVE))
        .contains("오늘은 회원가입 권한 요청 이탈이 주요 신호로 올라왔습니다.");
  }

  @Test
  @DisplayName("문단이 없으면 empty를 반환한다")
  void returnsEmptyWhenMinsuHasNotProducedOne() {
    allowMember();

    assertThat(signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, FROM, TO_EXCLUSIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("없는 프로젝트는 PROJECT_NOT_FOUND로 실패한다")
  void throwsProjectNotFoundWhenProjectMissing() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, FROM, TO_EXCLUSIVE))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  @DisplayName("워크스페이스 멤버가 아니면 거부한다")
  void throwsForbiddenForNonMember() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID)).thenReturn(Optional.empty());
    insertDigest("남의 프로젝트 문단", FROM);

    assertThatThrownBy(
            () -> signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, FROM, TO_EXCLUSIVE))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  private void allowMember() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  private void insertDigest(String summary, Instant createdAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO signal_digests (id, workspace_id, project_id, summary, created_at)"
                + " VALUES (?1, ?2, ?3, ?4, ?5)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, WORKSPACE_ID)
        .setParameter(3, PROJECT_ID)
        .setParameter(4, summary)
        .setParameter(5, createdAt)
        .executeUpdate();
    entityManager.clear();
  }
}
