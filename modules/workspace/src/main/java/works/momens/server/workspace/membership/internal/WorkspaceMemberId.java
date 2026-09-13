package works.momens.server.workspace.membership.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link WorkspaceMember}의 복합 식별자.
 *
 * <p>레거시 {@code workspace_members}는 {@code (workspace_id, user_id)} 복합 PK라 단일 UUID PK용 {@link
 * works.momens.server.common.persistence.BaseEntity}를 쓰지 않고 {@code @IdClass}로 둡니다. JPA
 * {@code @IdClass} 규약대로 public 클래스와 public no-arg 생성자, {@code equals}/{@code hashCode}를 둡니다.
 */
public class WorkspaceMemberId implements Serializable {

  private UUID workspaceId;
  private UUID userId;

  public WorkspaceMemberId() {}

  WorkspaceMemberId(UUID workspaceId, UUID userId) {
    this.workspaceId = workspaceId;
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkspaceMemberId that)) {
      return false;
    }
    return Objects.equals(workspaceId, that.workspaceId) && Objects.equals(userId, that.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workspaceId, userId);
  }
}
