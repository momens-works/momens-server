package works.momens.server.workspace.membership;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * 다른 멤버에게 부여할 수 있는 워크스페이스 역할입니다.
 *
 * <p>owner는 워크스페이스를 생성할 때만 정해지므로 이 enum에 포함하지 않습니다. 초대 생성, 멤버 추가, 역할 변경에서는 이 enum을 사용하므로 owner를 전달할
 * 수 없습니다. `workspace_invitations.role`의 CHECK 제약과 허용하는 값이 같아 이 컬럼의 도메인 enum으로도 사용합니다. 저장할 때는 대응하는
 * `WorkspaceRole`의 값을 사용합니다.
 */
public enum AssignableWorkspaceRole {
  ADMIN(WorkspaceRole.ADMIN),
  MEMBER(WorkspaceRole.MEMBER);

  private final WorkspaceRole workspaceRole;

  AssignableWorkspaceRole(WorkspaceRole workspaceRole) {
    this.workspaceRole = workspaceRole;
  }

  /** 저장 값을 부여할 수 있는 워크스페이스 역할로 변환합니다. 정의되지 않은 값이거나 owner이면 빈 `Optional`을 반환합니다. */
  public static Optional<AssignableWorkspaceRole> from(String value) {
    for (AssignableWorkspaceRole role : values()) {
      if (role.value().equals(value)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return workspaceRole.value();
  }

  /** 멤버십에 저장할 `WorkspaceRole`을 반환합니다. */
  public WorkspaceRole workspaceRole() {
    return workspaceRole;
  }
}
