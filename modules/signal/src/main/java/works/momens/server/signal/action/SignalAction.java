package works.momens.server.signal.action;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

/**
 * Signal당 action은 하나만 저장합니다. {@code signal_id}의 UNIQUE 제약으로 동시 요청의 멱등성을 보장합니다. convert만 결과 task를
 * 가지며 dismiss는 {@code null}입니다.
 */
@Getter
@Entity
@Table(name = "signal_actions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class SignalAction extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "signal_id", nullable = false, columnDefinition = "uuid")
  private UUID signalId;

  @Column(name = "action_type", nullable = false)
  private String actionType;

  @Column(name = "result_task_id", columnDefinition = "uuid")
  private UUID resultTaskId;

  @Column(name = "processed_by_user_id", nullable = false, columnDefinition = "uuid")
  private UUID processedByUserId;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  @Builder
  private SignalAction(
      UUID workspaceId,
      UUID signalId,
      String actionType,
      UUID resultTaskId,
      UUID processedByUserId) {
    this.workspaceId = workspaceId;
    this.signalId = signalId;
    this.actionType = actionType;
    this.resultTaskId = resultTaskId;
    this.processedByUserId = processedByUserId;
    this.processedAt = Instant.now();
  }
}
