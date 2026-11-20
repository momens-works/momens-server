package works.momens.server.mcp.grant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfig.class,
  McpGrantConfig.class,
  McpGrantIntegrationTest.McpTimeConfig.class
})
@DisplayName("MCP Grant 영속성 통합 테스트")
class McpGrantIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");

  @Autowired private McpGrantWriter mcpGrantWriter;
  @Autowired private McpGrantReader mcpGrantReader;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;
  @MockitoBean private McpTokenFamilyRevoker tokenFamilyRevoker;

  private UUID userId;
  private UUID workspaceId;

  @BeforeEach
  void setUpWorkspace() {
    workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "mcp-grant-test");
    userId = WorkspaceSeedSql.insertUser(entityManager, "mcp-grant-test@momens.works");
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?1, ?2, ?3)")
        .setParameter(1, workspaceId)
        .setParameter(2, userId)
        .setParameter(3, "member")
        .executeUpdate();
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  @Test
  @DisplayName("저장한 Grant를 활성 상태로 조회한다")
  void persistsGrantAndReadsItAsActive() {
    McpGrantDetail created = createGrant();

    entityManager.flush();
    entityManager.clear();

    McpGrantDetail persisted = mcpGrantReader.findActive(created.id()).orElseThrow();

    assertThat(persisted).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(created);
    assertThat(persisted.createdAt()).isNotNull();
  }

  @Test
  @DisplayName("같은 사용자·client·workspace의 활성 Grant 중복을 막는다")
  void doesNotAllowTwoActiveGrantsForTheSameClientAndWorkspace() {
    createGrant();

    assertThatThrownBy(this::createGrant)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_CONFLICT));
  }

  @Test
  @DisplayName("Grant 철회 후 활성 조회에서 제외하고 token family port를 호출한다")
  void revokeHidesGrantAndCallsTokenFamilyPort() {
    McpGrantDetail created = createGrant();
    Instant revokedAt = NOW.plusSeconds(30);

    mcpGrantWriter.revoke(created.id(), revokedAt);

    assertThat(mcpGrantReader.findActive(created.id())).isEmpty();
    verify(tokenFamilyRevoker).revokeByGrantId(created.id(), revokedAt);
  }

  @Test
  @DisplayName("사용 시각은 앞으로만 갱신하며 폐기한 grant에는 쓰지 않는다")
  void recordsUsageWithoutMovingTimeBackwardsOrRevivingRevokedGrant() {
    McpGrantDetail grant = createGrant();
    Instant usedAt = NOW.plusSeconds(60);
    assertThat(mcpGrantWriter.recordUsage(grant.id(), usedAt)).isTrue();
    assertThat(mcpGrantWriter.recordUsage(grant.id(), NOW)).isTrue();
    entityManager.clear();
    assertThat(mcpGrantReader.findActiveConnections(workspaceId, userId).getFirst().lastUsedAt())
        .isEqualTo(usedAt);
    mcpGrantWriter.revoke(grant.id(), NOW.plusSeconds(90));
    entityManager.flush();
    assertThat(mcpGrantWriter.recordUsage(grant.id(), NOW.plusSeconds(120))).isFalse();
    entityManager.clear();
    assertThat(entityManager.find(McpGrant.class, grant.id()).getLastUsedAt()).isEqualTo(usedAt);
    assertThat(mcpGrantReader.findActiveConnections(workspaceId, userId)).isEmpty();
  }

  @Test
  @DisplayName("활성 연결 목록은 사용자와 워크스페이스로 제한한다")
  void connectionListIsScopedToOwnerAndWorkspace() {
    McpGrantDetail grant = createGrant();
    assertThat(mcpGrantReader.findActiveConnections(workspaceId, userId))
        .extracting(connection -> connection.id())
        .containsExactly(grant.id());
    assertThat(mcpGrantReader.findActiveConnections(workspaceId, UUID.randomUUID())).isEmpty();
    assertThat(mcpGrantReader.findActiveConnections(UUID.randomUUID(), userId)).isEmpty();
  }

  @Test
  @DisplayName("이미 읽은 grant를 폐기해도 별도로 기록된 사용 시각을 덮어쓰지 않는다")
  void revokePreservesUsageWrittenAfterEntityWasLoaded() {
    McpGrantDetail grant = createGrant();
    Instant usedAt = NOW.plusSeconds(60);
    mcpGrantWriter.recordUsage(grant.id(), usedAt);
    mcpGrantWriter.revoke(grant.id(), NOW.plusSeconds(90));
    entityManager.flush();
    entityManager.clear();
    McpGrant revoked = entityManager.find(McpGrant.class, grant.id());
    assertThat(revoked.getLastUsedAt()).isEqualTo(usedAt);
    assertThat(revoked.getRevokedAt()).isNotNull();
  }

  private McpGrantDetail createGrant() {
    return mcpGrantWriter.create(
        new CreateMcpGrantCommand(
            userId, "client-1", workspaceId, List.of("mcp:tasks:write", "mcp:projects:read")));
  }

  @TestConfiguration
  static class McpTimeConfig {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
