package works.momens.server.web;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.workspace.core.WorkspaceErrorCode;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 존재 여부를 역할보다 먼저 확인해 없는 대상에는 404를 반환합니다.
 *
 * <p>권한 부족의 {@code details} 형식은 이 클래스에서 관리합니다.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceAccessChecker {

  private final WorkspaceReader workspaceReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  public void requireWorkspaceExists(UUID workspaceId) {
    if (workspaceReader.findById(workspaceId).isEmpty()) {
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_NOT_FOUND, Map.of("workspace_id", workspaceId.toString()));
    }
  }

  public void requireRoleAtLeast(UUID workspaceId, UUID userId, WorkspaceRole required) {
    requireRoleAtLeast(
        workspaceId, workspaceMembershipReader.roleOf(workspaceId, userId), required);
  }

  /**
   * 이미 조회한 역할을 기준으로 권한을 판정합니다. 호출자가 멤버십을 조회할 때 요청자의 역할도 함께 확인한 경우, 역할을 다시 조회하지 않도록 이 메서드를 사용합니다.
   *
   * <p>비어 있는 {@link Optional}은 요청자가 워크스페이스의 멤버가 아님을 의미합니다.
   */
  public void requireRoleAtLeast(
      UUID workspaceId, Optional<WorkspaceRole> grantedRole, WorkspaceRole required) {
    if (grantedRole.filter(role -> role.isAtLeast(required)).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN,
          Map.of("workspace_id", workspaceId.toString(), "required_role", required.value()));
    }
  }
}
