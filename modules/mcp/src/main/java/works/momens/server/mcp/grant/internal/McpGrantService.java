package works.momens.server.mcp.grant.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantConnection;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@RequiredArgsConstructor
class McpGrantService implements McpGrantWriter, McpGrantReader {

  private static final String ACTIVE_SUBJECT_CONSTRAINT = "uq_mcp_grants_active_subject";

  private final McpGrantRepository mcpGrantRepository;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final McpTokenFamilyRevoker tokenFamilyRevoker;
  private final Clock clock;

  @Override
  @Transactional
  public McpGrantDetail create(CreateMcpGrantCommand command) {
    validate(command);
    requireWorkspaceMember(command.userId(), command.workspaceId());
    lockSubject(command);
    return createValidated(command);
  }

  @Override
  @Transactional
  public McpGrantDetail replace(CreateMcpGrantCommand command) {
    validate(command);
    requireWorkspaceMember(command.userId(), command.workspaceId());
    lockSubject(command);
    mcpGrantRepository
        .findByUserIdAndClientIdAndWorkspaceIdAndRevokedAtIsNull(
            command.userId(), command.clientId(), command.workspaceId())
        .ifPresent(grant -> {
          Instant now = clock.instant();
          // Use the same grant-before-token lock order as explicit revocation.
          // A concurrent revoke may already have closed this lifecycle.
          if (mcpGrantRepository.revokeActive(grant.getId(), now) == 1) {
            tokenFamilyRevoker.revokeByGrantId(grant.getId(), now);
          }
        });
    return createValidated(command);
  }

  private void lockSubject(CreateMcpGrantCommand command) {
    mcpGrantRepository.lockSubject(
        command.userId() + ":" + command.clientId() + ":" + command.workspaceId());
  }

  private McpGrantDetail createValidated(CreateMcpGrantCommand command) {
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
    try {
      return toDetail(mcpGrantRepository.saveAndFlush(grant));
    } catch (DataIntegrityViolationException exception) {
      if (violatesConstraint(exception, ACTIVE_SUBJECT_CONSTRAINT)) {
        throw new BusinessException(
            CommonErrorCode.COMMON_CONFLICT,
            Map.of(
                "user_id", command.userId(),
                "client_id", command.clientId(),
                "workspace_id", command.workspaceId()));
      }
      throw exception;
    }
  }

  @Override
  @Transactional
  public void revoke(UUID grantId, Instant revokedAt) {
    Instant effectiveRevokedAt = revokedAt != null ? revokedAt : clock.instant();
    if (mcpGrantRepository.revokeActive(grantId, effectiveRevokedAt) != 1) {
      throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND, Map.of("grant_id", grantId));
    }
    tokenFamilyRevoker.revokeByGrantId(grantId, effectiveRevokedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<McpGrantDetail> findActive(UUID grantId) {
    return mcpGrantRepository.findByIdAndRevokedAtIsNull(grantId).map(McpGrantService::toDetail);
  }

  @Override
  @Transactional(readOnly = true)
  public List<McpGrantConnection> findActiveConnections(UUID workspaceId, UUID userId) {
    return mcpGrantRepository
        .findByWorkspaceIdAndUserIdAndRevokedAtIsNullOrderByCreatedAtDescIdAsc(workspaceId, userId)
        .stream()
        .map(grant ->
            new McpGrantConnection(
                grant.getId(),
                grant.getClientId(),
                grant.getUserId(),
                grant.getWorkspaceId(),
                grant.getScopes(),
                grant.getLastUsedAt(),
                grant.getCreatedAt()))
        .toList();
  }

  @Override
  @Transactional
  public boolean recordUsage(UUID grantId, Instant usedAt) {
    return mcpGrantRepository.recordUsage(grantId, usedAt) == 1;
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

  private static boolean violatesConstraint(Throwable exception, String constraintName) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof ConstraintViolationException violation
          && constraintName.equals(violation.getConstraintName())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
