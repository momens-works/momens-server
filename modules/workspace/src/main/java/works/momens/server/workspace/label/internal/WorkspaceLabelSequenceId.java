package works.momens.server.workspace.label.internal;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link WorkspaceLabelSequence}의 복합 식별자.
 *
 * <p>레거시 {@code workspace_label_sequences}는 {@code (workspace_id, label_prefix)} 복합 PK라 단일 UUID PK용
 * {@link works.momens.server.common.persistence.BaseEntity}를 쓰지 않고 {@code @IdClass}로 둡니다. JPA
 * {@code @IdClass} 규약대로 public 클래스와 public no-arg 생성자, {@code equals}/{@code hashCode}를 둡니다.
 */
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
