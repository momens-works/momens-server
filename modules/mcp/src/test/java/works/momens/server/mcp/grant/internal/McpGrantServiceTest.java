package works.momens.server.mcp.grant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@ExtendWith(MockitoExtension.class)
class McpGrantServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");

  @Mock private McpGrantRepository mcpGrantRepository;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private McpTokenFamilyRevoker tokenFamilyRevoker;

  private McpGrantService service;

  @BeforeEach
  void setUp() {
    service =
        new McpGrantService(
            mcpGrantRepository,
            workspaceMembershipReader,
            tokenFamilyRevoker,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createsGrantOnlyForWorkspaceMember() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(mcpGrantRepository.existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
            userId, "client-1", workspaceId))
        .thenReturn(false);
    when(mcpGrantRepository.saveAndFlush(any(McpGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var detail =
        service.create(
            new CreateMcpGrantCommand(
                userId, "client-1", workspaceId, List.of("mcp:tasks:write", "mcp:projects:read")));

    assertThat(detail.userId()).isEqualTo(userId);
    assertThat(detail.workspaceId()).isEqualTo(workspaceId);
    assertThat(detail.scopes()).containsExactly("mcp:projects:read", "mcp:tasks:write");
    assertThat(detail.approvedAt()).isEqualTo(NOW);
  }

  @Test
  void mapsConcurrentActiveGrantConflict() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(mcpGrantRepository.existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
            userId, "client-1", workspaceId))
        .thenReturn(false);
    when(mcpGrantRepository.saveAndFlush(any(McpGrant.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate active grant",
                new ConstraintViolationException(
                    "duplicate active grant", null, "uq_mcp_grants_active_subject")));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateMcpGrantCommand(
                        userId, "client-1", workspaceId, List.of("mcp:tasks:read"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_CONFLICT));
  }

  @Test
  void rethrowsUnrelatedConstraintViolation() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(mcpGrantRepository.existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
            userId, "client-1", workspaceId))
        .thenReturn(false);
    DataIntegrityViolationException exception =
        new DataIntegrityViolationException(
            "unsupported scope",
            new ConstraintViolationException("unsupported scope", null, "ck_mcp_grants_scopes"));
    when(mcpGrantRepository.saveAndFlush(any(McpGrant.class))).thenThrow(exception);

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateMcpGrantCommand(
                        userId, "client-1", workspaceId, List.of("mcp:tasks:read"))))
        .isSameAs(exception);
  }

  @Test
  void rejectsGrantForAnotherWorkspace() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(workspaceMembershipReader.roleOf(workspaceId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateMcpGrantCommand(
                        userId, "client-1", workspaceId, List.of("mcp:tasks:read"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_FORBIDDEN));
    verify(mcpGrantRepository, never())
        .existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(any(), any(), any());
  }

  @Test
  void revokingGrantAlsoRevokesItsTokenFamily() {
    UUID grantId = UUID.randomUUID();
    McpGrant grant =
        McpGrant.create(
            new CreateMcpGrantCommand(
                UUID.randomUUID(), "client-1", UUID.randomUUID(), List.of("mcp:tasks:read")),
            NOW);
    when(mcpGrantRepository.findByIdAndRevokedAtIsNull(grantId)).thenReturn(Optional.of(grant));

    service.revoke(grantId, NOW.plusSeconds(30));

    assertThat(grant.getRevokedAt()).isEqualTo(NOW.plusSeconds(30));
    verify(tokenFamilyRevoker).revokeByGrantId(grantId, NOW.plusSeconds(30));
  }
}
