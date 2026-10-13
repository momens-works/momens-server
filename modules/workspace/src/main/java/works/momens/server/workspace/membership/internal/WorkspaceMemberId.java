package works.momens.server.workspace.membership.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

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
