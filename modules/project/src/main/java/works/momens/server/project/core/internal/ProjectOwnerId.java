package works.momens.server.project.core.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProjectOwnerId implements Serializable {

  private UUID projectId;
  private UUID ownerUserId;

  public ProjectOwnerId() {}

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProjectOwnerId that)) {
      return false;
    }
    return Objects.equals(projectId, that.projectId)
        && Objects.equals(ownerUserId, that.ownerUserId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectId, ownerUserId);
  }
}
