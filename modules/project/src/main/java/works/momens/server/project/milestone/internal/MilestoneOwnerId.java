package works.momens.server.project.milestone.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class MilestoneOwnerId implements Serializable {

  private UUID milestoneId;
  private UUID ownerUserId;

  public MilestoneOwnerId() {}

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MilestoneOwnerId that)) {
      return false;
    }
    return Objects.equals(milestoneId, that.milestoneId)
        && Objects.equals(ownerUserId, that.ownerUserId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(milestoneId, ownerUserId);
  }
}
