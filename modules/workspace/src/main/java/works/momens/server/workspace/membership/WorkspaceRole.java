package works.momens.server.workspace.membership;

import java.util.Optional;

/**
 * workspace 멤버십 역할.
 *
 * <p>저장 값은 {@code workspace_members.role}의 DB CHECK 제약에 정의된 세 가지 값과 같습니다. 역할을 추가하거나 제거할 때는 마이그레이션과
 * 이 enum을 함께 변경합니다. task의 기능 역할(pm, android 등)과는 다른 개념입니다.
 *
 * <p>역할 간 서열도 이 enum이 관리합니다. 각 작업에 필요한 역할은 호출 측에서 정하고, 현재 역할이 요구 수준을 충족하는지는 {@link #isAtLeast}가
 * 판단합니다.
 */
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

  /** 소문자로 저장된 값을 역할로 변환합니다. 정의된 저장 값이 아니면 빈 Optional을 반환합니다. */
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
