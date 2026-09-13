package works.momens.server.workspace.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.UserWorkspaceMembership;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * {@code WorkspaceReader} 공개 API를 검증합니다.
 *
 * <p>실제 PostgreSQL(Testcontainers) 환경에서 생성 시각 내림차순 정렬, ID 필터링, {@code description}이 {@code null}인
 * 경우의 동작을 검증합니다 ({@code docs/design/legacy-product-api-migration/slice-workspace-read.md} 4.3절).
 *
 * <p>멤버십 조회는 다른 하위 도메인의 공개 API이므로 {@code @MockitoBean}으로 대체합니다. 실제 멤버십에 따라 목록이 필터링되는지는 애플리케이션 레벨 통합
 * 테스트인 {@code WebWorkspacesIntegrationTest}에서 검증합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, WorkspaceReaderImpl.class})
class WorkspaceReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private WorkspaceReader workspaceReader;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  @DisplayName("멤버인 워크스페이스만 생성 시각 내림차순으로 조회한다")
  void listByMemberUserIdReturnsOnlyMemberWorkspacesSortedByCreatedAtDesc() {
    UUID user = WorkspaceSeedSql.insertUser(entityManager, "reader-it-user@momens.works");
    UUID first = WorkspaceSeedSql.insertWorkspace(entityManager, "reader-it-first");
    UUID second = WorkspaceSeedSql.insertWorkspace(entityManager, "reader-it-second");
    UUID others = WorkspaceSeedSql.insertWorkspace(entityManager, "reader-it-others");
    when(workspaceMembershipReader.listUserMemberships(user))
        .thenReturn(
            List.of(
                new UserWorkspaceMembership(first, "owner"),
                new UserWorkspaceMembership(second, "member")));
    // 같은 트랜잭션 안의 NOW() 기본값은 같은 시각을 반환할 수 있어(postgres 트랜잭션 스냅샷), 정렬 검증을 위해
    // created_at을 명시적으로 다른 값으로 고정한다.
    Instant now = Instant.now();
    updateCreatedAt(first, now.minusSeconds(60));
    updateCreatedAt(second, now);
    updateCreatedAt(others, now.minusSeconds(30));
    entityManager.flush();
    entityManager.clear();

    List<WorkspaceDetail> result = workspaceReader.listByMemberUserId(user);

    assertThat(result).extracting(WorkspaceDetail::id).containsExactly(second, first);
  }

  @Test
  @DisplayName("멤버십이 없으면 빈 목록을 반환한다")
  void listByMemberUserIdIsEmptyWhenUserHasNoMembership() {
    UUID user = WorkspaceSeedSql.insertUser(entityManager, "reader-it-lonely@momens.works");
    when(workspaceMembershipReader.listUserMemberships(user)).thenReturn(List.of());

    assertThat(workspaceReader.listByMemberUserId(user)).isEmpty();
  }

  @Test
  @DisplayName("description이 설정돼 있으면 그대로 조회한다")
  void findByIdReturnsDescriptionWhenSet() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "reader-it-desc");
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE workspaces SET description = ?1 WHERE id = ?2")
        .setParameter(1, "제품팀 워크스페이스")
        .setParameter(2, workspaceId)
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();

    Optional<WorkspaceDetail> result = workspaceReader.findById(workspaceId);

    assertThat(result).isPresent();
    assertThat(result.get().description()).isEqualTo("제품팀 워크스페이스");
  }

  @Test
  @DisplayName("description이 없으면 null로 조회한다")
  void findByIdReturnsNullDescriptionWhenNotSet() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "reader-it-no-desc");
    entityManager.flush();
    entityManager.clear();

    Optional<WorkspaceDetail> result = workspaceReader.findById(workspaceId);

    assertThat(result).isPresent();
    assertThat(result.get().description()).isNull();
  }

  @Test
  @DisplayName("워크스페이스가 없으면 빈 Optional을 반환한다")
  void findByIdIsEmptyWhenWorkspaceDoesNotExist() {
    assertThat(workspaceReader.findById(UUID.randomUUID())).isEmpty();
  }

  private void updateCreatedAt(UUID workspaceId, Instant createdAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE workspaces SET created_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, workspaceId)
        .executeUpdate();
  }
}
