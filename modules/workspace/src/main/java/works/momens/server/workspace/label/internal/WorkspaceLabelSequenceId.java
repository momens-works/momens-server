package works.momens.server.workspace.label.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class WorkspaceLabelSequenceId implements Serializable {

  private UUID workspaceId;
  private String labelPrefix;

  public WorkspaceLabelSequenceId() {}

  WorkspaceLabelSequenceId(UUID workspaceId, String labelPrefix) {
    this.workspaceId = workspaceId;
    this.labelPrefix = labelPrefix;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkspaceLabelSequenceId that)) {
      return false;
    }
    return Objects.equals(workspaceId, that.workspaceId)
        && Objects.equals(labelPrefix, that.labelPrefix);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workspaceId, labelPrefix);
  }
}
