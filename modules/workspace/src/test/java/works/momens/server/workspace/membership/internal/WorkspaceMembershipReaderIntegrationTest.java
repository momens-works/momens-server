package works.momens.server.workspace.membership.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 멤버십 조회 public API를 검증합니다.
 *
 * <p>PostgreSQL에서 역할과 감사 시각을 함께 반환하는지, 다른 워크스페이스의 멤버가 결과에 포함되지 않는지, 확정된 기준에 따라 정렬되는지 확인합니다. 정렬을
 * SQL에서 처리하므로 실제 데이터베이스를 사용해 검증합니다. 역할 조회 결과가 저장된 값을 그대로 반환하는지도 함께 검증합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, WorkspaceMembershipReaderImpl.class})
class WorkspaceMembershipReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private WorkspaceMembershipReader workspaceMembershipReader;
  @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("역할과 멤버가 된 시각을 함께 반환한다")
  void listDetailsReturnsRoleAndAuditFields() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-roster");
    UUID owner = WorkspaceSeedSql.insertUser(entityManager, "owner@momens.works");
    UUID member = WorkspaceSeedSql.insertUser(entityManager, "member@momens.works");
    addMember(workspaceId, owner, "owner");
    addMember(workspaceId, member, "member");
    entityManager.flush();

    List<WorkspaceMembershipDetail> details =
        workspaceMembershipReader.listMembershipDetails(workspaceId);

    assertThat(details).hasSize(2);
    assertThat(details)
        .extracting(WorkspaceMembershipDetail::userId, WorkspaceMembershipDetail::role)
        .containsExactlyInAnyOrder(tuple(owner, "owner"), tuple(member, "member"));
    assertThat(details)
        .allSatisfy(
            detail -> {
              assertThat(detail.createdAt()).isNotNull();
              assertThat(detail.updatedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("요청한 워크스페이스의 멤버만 반환한다")
  void listDetailsReturnsOnlyMembersOfGivenWorkspace() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-mine");
    UUID otherWorkspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-other");
    UUID member = WorkspaceSeedSql.insertUser(entityManager, "mine@momens.works");
    UUID otherMember = WorkspaceSeedSql.insertUser(entityManager, "other@momens.works");
    addMember(workspaceId, member, "member");
    addMember(otherWorkspaceId, otherMember, "member");
    entityManager.flush();

    assertThat(workspaceMembershipReader.listMembershipDetails(workspaceId))
        .extracting(WorkspaceMembershipDetail::userId)
        .containsExactly(member);
  }

  @Test
  @DisplayName("멤버가 없으면 빈 목록을 반환한다")
  void listDetailsIsEmptyForWorkspaceWithoutMembers() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-nobody");

    assertThat(workspaceMembershipReader.listMembershipDetails(workspaceId)).isEmpty();
  }

  @Test
  @DisplayName("가입 시각 오름차순으로 정렬하고 가입 시각이 같으면 사용자 식별자 오름차순으로 정렬한다")
  void listDetailsSortsByCreatedAtThenUserId() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-sorted");
    // 두 사용자 식별자의 16진수 첫 자리를 각각 8 이상과 8 미만으로 고정합니다.
    // Java의 UUID 비교는 상위 64비트를 부호 있는 값으로 다뤄 PostgreSQL과 반대 순서로 판정하므로,
    // 정렬이 SQL 밖에서 처리되면 해당 테스트가 실패합니다.
    UUID signBitSet = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    UUID signBitClear = UUID.fromString("0aaaaaaa-0000-4000-8000-000000000001");
    UUID joinedLater = UUID.fromString("30000000-0000-4000-8000-000000000001");
    insertUser(signBitSet, "sign-bit-set@momens.works");
    insertUser(signBitClear, "sign-bit-clear@momens.works");
    insertUser(joinedLater, "joined-later@momens.works");
    insertMember(workspaceId, joinedLater, "member", Instant.parse("2026-08-10T00:00:00Z"));
    insertMember(workspaceId, signBitSet, "owner", Instant.parse("2026-08-01T00:00:00Z"));
    insertMember(workspaceId, signBitClear, "member", Instant.parse("2026-08-01T00:00:00Z"));

    assertThat(workspaceMembershipReader.listMembershipDetails(workspaceId))
        .extracting(WorkspaceMembershipDetail::userId)
        .containsExactly(signBitClear, signBitSet, joinedLater);
  }

  @Test
  @DisplayName("멤버십에 저장된 역할을 그대로 반환한다")
  void roleOfReturnsStoredRole() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-role");
    UUID owner = WorkspaceSeedSql.insertUser(entityManager, "role-owner@momens.works");
    UUID member = WorkspaceSeedSql.insertUser(entityManager, "role-member@momens.works");
    addMember(workspaceId, owner, "owner");
    addMember(workspaceId, member, "member");
    entityManager.flush();
    entityManager.clear();

    assertThat(workspaceMembershipReader.roleOf(workspaceId, owner)).contains(WorkspaceRole.OWNER);
    assertThat(workspaceMembershipReader.roleOf(workspaceId, member))
        .contains(WorkspaceRole.MEMBER);
  }

  @Test
  @DisplayName("멤버가 아니면 빈 Optional을 반환한다")
  void roleOfIsEmptyWhenUserIsNotMember() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-stranger");
    UUID stranger = WorkspaceSeedSql.insertUser(entityManager, "stranger@momens.works");
    entityManager.flush();
    entityManager.clear();

    assertThat(workspaceMembershipReader.roleOf(workspaceId, stranger)).isEmpty();
  }

  @Test
  @DisplayName("요청한 워크스페이스의 멤버 식별자만 반환합니다")
  void listMemberUserIdsReturnsMembersOfGivenWorkspace() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-ids");
    UUID otherWorkspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-ids-other");
    UUID member = WorkspaceSeedSql.insertUser(entityManager, "ids-mine@momens.works");
    UUID otherMember = WorkspaceSeedSql.insertUser(entityManager, "ids-other@momens.works");
    addMember(workspaceId, member, "member");
    addMember(otherWorkspaceId, otherMember, "member");
    entityManager.flush();

    assertThat(workspaceMembershipReader.listMemberUserIds(workspaceId)).containsExactly(member);
  }

  private void insertUser(UUID userId, String email) {
    entityManager
        .getEntityManager()
        .createNativeQuery("INSERT INTO users (id, email, name) VALUES (?1, ?2, ?3)")
        .setParameter(1, userId)
        .setParameter(2, email)
        .setParameter(3, "이름")
        .executeUpdate();
  }

  private void insertMember(UUID workspaceId, UUID userId, String role, Instant joinedAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role, created_at, updated_at)
            VALUES (?1, ?2, ?3, ?4, ?4)
            """)
        .setParameter(1, workspaceId)
        .setParameter(2, userId)
        .setParameter(3, role)
        .setParameter(4, joinedAt)
        .executeUpdate();
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    workspaceMemberRepository.save(
        WorkspaceMember.builder().workspaceId(workspaceId).userId(userId).role(role).build());
  }
}
