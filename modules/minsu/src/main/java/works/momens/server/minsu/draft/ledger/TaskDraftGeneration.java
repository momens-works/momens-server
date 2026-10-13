package works.momens.server.minsu.draft.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;

/**
 * Minsu task draft 비동기 생성 원장.
 *
 * <p>baseline은 convert가 {@code tasks}에 실제로 쓴 title·role·priority를 복사한 값이다. 반영 시 fallback 규칙을 재계산하면
 * 규칙 변경 전 적재된 작업을 {@code user_edited}로 오분류할 수 있다.
 *
 * <p>evidence snapshot은 jsonb로 보관하고 프롬프트를 만들 때 역직렬화한다. 원장은 evidence의 내부 구조를 해석하지 않는다.
 */
@Getter
@Entity
@Table(name = "minsu_task_draft_generations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TaskDraftGeneration extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
  private UUID taskId;

  @Column(name = "signal_title", nullable = false)
  private String signalTitle;

  @Column(name = "signal_type", nullable = false)
  private String signalType;

  @Column(name = "signal_description", nullable = false)
  private String signalDescription;

  @Column(name = "signal_impact")
  private String signalImpact;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "signal_evidence", nullable = false, columnDefinition = "jsonb")
  private String signalEvidence;

  @Column(name = "baseline_title", nullable = false)
  private String baselineTitle;

  @Column(name = "baseline_role", nullable = false)
  private String baselineRole;

  @Column(name = "baseline_priority", nullable = false)
  private String baselinePriority;

  @Column(name = "read_deadline_at", nullable = false)
  private Instant readDeadlineAt;

  @Column(name = "apply_cutoff_at", nullable = false)
  private Instant applyCutoffAt;

  @Column(nullable = false)
  private String status;

  @Column(name = "completion_reason")
  private String completionReason;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "claim_token", columnDefinition = "uuid")
  private UUID claimToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  /** convert 트랜잭션이 적재하는 유일한 생성 경로다. 적재 직후 상태는 항상 {@code pending}이고 시도 횟수는 0이다. */
  @Builder
  TaskDraftGeneration(
      UUID workspaceId,
      UUID taskId,
      String signalTitle,
      String signalType,
      String signalDescription,
      String signalImpact,
      String signalEvidence,
      String baselineTitle,
      String baselineRole,
      String baselinePriority,
      Instant readDeadlineAt,
      Instant applyCutoffAt,
      Instant nextAttemptAt) {
    this.workspaceId = workspaceId;
    this.taskId = taskId;
    this.signalTitle = signalTitle;
    this.signalType = signalType;
    this.signalDescription = signalDescription;
    this.signalImpact = signalImpact;
    this.signalEvidence = signalEvidence;
    this.baselineTitle = baselineTitle;
    this.baselineRole = baselineRole;
    this.baselinePriority = baselinePriority;
    this.readDeadlineAt = readDeadlineAt;
    this.applyCutoffAt = applyCutoffAt;
    this.nextAttemptAt = nextAttemptAt;
    this.status = GenerationStatus.PENDING.value();
  }

  boolean isProcessing() {
    return GenerationStatus.PROCESSING.value().equals(status);
  }

  /** 이번 시도로 상한에 도달하는지. 시도 횟수는 claim 시점에 이미 증가한 값이다. */
  boolean isExhausted(int maxAttempts) {
    return attemptCount >= maxAttempts;
  }

  /**
   * 반영 창을 지났는지. 읽기 투영보다 margin만큼 이른 시각이라, 여기서 참이면 앱은 아직 {@code generating}을 보고 있을 수 있다.
   *
   * <p>비교 시각은 호출자가 DB 시계에서 읽어 넘긴다. 애플리케이션 시계로 비교하면 인스턴스 간 시계 편차가 그대로 반영 여부의 편차가 된다.
   */
  boolean isPastApplyCutoff(Instant now) {
    return !now.isBefore(applyCutoffAt);
  }

  /**
   * 시도를 claim한다. 시도 횟수를 <b>이 시점에</b> 올리는 것이 핵심이다.
   *
   * <p>결과 기록 시점에 올리면 claim 후 프로세스가 죽은 시도는 세지 않아 상한에 영원히 도달하지 못한다. lease 만료로 회수될 때마다 같은 작업이 다시 시도되고
   * 그 사이 provider 호출 비용은 계속 발생한다.
   */
  void claim(UUID claimToken, Instant leaseExpiresAt) {
    this.attemptCount++;
    this.status = GenerationStatus.PROCESSING.value();
    this.claimToken = claimToken;
    this.leaseExpiresAt = leaseExpiresAt;
  }

  boolean isClaimedBy(UUID claimToken) {
    return isProcessing() && claimToken.equals(this.claimToken);
  }

  /**
   * retryable 실패를 {@code pending}으로 되돌린다.
   *
   * <p>{@code processing}을 유지한 채 token만 비우면 "claim 보유 중"이라는 상태 의미와 어긋나고, 마이그레이션의 claim CHECK에도 걸린다.
   * 이전 token과 lease를 함께 정리한다.
   */
  void scheduleRetry(Instant nextAttemptAt) {
    this.status = GenerationStatus.PENDING.value();
    this.claimToken = null;
    this.leaseExpiresAt = null;
    this.nextAttemptAt = nextAttemptAt;
  }

  /** 종료한다. 사유는 상태가 아니라 별도 컬럼이며 둘의 정합은 마이그레이션의 CHECK가 지킨다. */
  void complete(CompletionReason reason) {
    this.status = GenerationStatus.COMPLETED.value();
    this.completionReason = reason.value();
    this.claimToken = null;
    this.leaseExpiresAt = null;
  }
}
