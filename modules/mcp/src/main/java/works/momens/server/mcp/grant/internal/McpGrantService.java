package works.momens.server.mcp.grant.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@RequiredArgsConstructor
class McpGrantService implements McpGrantWriter, McpGrantReader {

  private final McpGrantRepository mcpGrantRepository;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final McpTokenFamilyRevoker tokenFamilyRevoker;
  private final Clock clock;

  @Override
  @Transactional
  public McpGrantDetail create(CreateMcpGrantCommand command) {
    validate(command);
    requireWorkspaceMember(command.userId(), command.workspaceId());
    if (mcpGrantRepository.existsByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
        command.userId(), command.clientId(), command.workspaceId())) {
      throw new BusinessException(
          CommonErrorCode.COMMON_CONFLICT,
          Map.of(
              "user_id", command.userId(),
              "client_id", command.clientId(),
              "workspace_id", command.workspaceId()));
    }
    McpGrant grant;
    try {
      grant = McpGrant.create(command, clock.instant());
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(CommonErrorCode.COMMON_VALIDATION_FAILED, exception.getMessage());
    }
    return toDetail(mcpGrantRepository.save(grant));
  }

  @Override
  @Transactional
  public void revoke(UUID grantId, Instant revokedAt) {
    McpGrant grant =
        mcpGrantRepository
            .findByIdAndRevokedAtIsNull(grantId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        CommonErrorCode.COMMON_NOT_FOUND, Map.of("grant_id", grantId)));
    Instant effectiveRevokedAt = revokedAt != null ? revokedAt : clock.instant();
    grant.revoke(effectiveRevokedAt);
    tokenFamilyRevoker.revokeByGrantId(grantId, effectiveRevokedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<McpGrantDetail> findActive(UUID grantId) {
    return mcpGrantRepository.findByIdAndRevokedAtIsNull(grantId).map(McpGrantService::toDetail);
  }

  private void requireWorkspaceMember(UUID userId, UUID workspaceId) {
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("workspace_id", workspaceId));
    }
  }

  private static void validate(CreateMcpGrantCommand command) {
    if (command == null
        || command.userId() == null
        || command.workspaceId() == null
        || command.clientId() == null
        || command.clientId().isBlank()) {
      throw new BusinessException(CommonErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static McpGrantDetail toDetail(McpGrant grant) {
    return new McpGrantDetail(
        grant.getId(),
        grant.getUserId(),
        grant.getClientId(),
        grant.getWorkspaceId(),
        grant.getScopes(),
        grant.getApprovedAt(),
        grant.getRevokedAt(),
        grant.getCreatedAt());
  }
}
