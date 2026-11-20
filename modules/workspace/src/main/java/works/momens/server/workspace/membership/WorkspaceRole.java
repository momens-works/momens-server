package works.momens.server.workspace.membership;

import java.util.Optional;

/** 워크스페이스 접근 역할과 서열을 정의합니다. 태스크의 기능 역할과는 별개입니다. 필요한 역할 수준은 호출자가 정합니다. */
public enum WorkspaceRole {
  MEMBER("member", 0),
  ADMIN("admin", 1),
  OWNER("owner", 2);

  private final String value;
  private final int rank;

  WorkspaceRole(String value, int rank) {
    this.value = value;
    this.rank = rank;
  }

  public static Optional<WorkspaceRole> from(String value) {
    for (WorkspaceRole role : values()) {
      if (role.value.equals(value)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }

  public String value() {
    return value;
  }

  /** 현재 역할이 required 이상의 권한인지 확인합니다. 역할 서열은 owner, admin, member 순입니다. */
  public boolean isAtLeast(WorkspaceRole required) {
    return rank >= required.rank;
  }
}
