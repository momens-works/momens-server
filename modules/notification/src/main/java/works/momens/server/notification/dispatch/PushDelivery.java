package works.momens.server.notification.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 기기별 push 발송 상태.
 *
 * <p>{@code (outbox_event_id, installation_id)} 복합 PK가 동일 event의 기기별 중복 materialization을 막으므로 공유
 * 베이스 엔티티(UUID PK)를 상속하지 않는다. 행 생성은 native {@code ON CONFLICT DO NOTHING} insert가 담당하고, JPA는 클레임·상태
 * 전이(update)만 다룬다. token은 복제하지 않고 installation을 참조한다.
 */
@Getter
@Entity
@Table(name = "push_deliveries")
@IdClass(PushDeliveryId.class)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDelivery {

  static final int MAX_ATTEMPTS = 4;

  @Id
  @Column(name = "outbox_event_id")
  private long outboxEventId;

  @Id
  @Column(name = "installation_id", columnDefinition = "uuid")
  private UUID installationId;

  @Column(name = "target_user_id", nullable = false, columnDefinition = "uuid")
  private UUID targetUserId;

  @Column(nullable = false)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "claim_token", columnDefinition = "uuid")
  private UUID claimToken;

  @Column(name = "failure_category")
  private String failureCategory;

  @Column(name = "sent_at")
  private Instant sentAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  boolean isPending() {
    return DeliveryStatus.PENDING.value().equals(status);
  }

  boolean isExhausted() {
    return attemptCount >= MAX_ATTEMPTS;
  }

  /** 전송 시도를 클레임한다. 시도 횟수와 claim token, 처리 lease 만료 시각을 기록한다. */
  void claimAttempt(UUID claimToken, Instant leaseUntil) {
    this.attemptCount++;
    this.claimToken = claimToken;
    this.nextAttemptAt = leaseUntil;
  }

  boolean isClaimedBy(UUID claimToken) {
    return isPending() && claimToken.equals(this.claimToken);
  }

  void renewLease(Instant leaseUntil) {
    this.nextAttemptAt = leaseUntil;
  }

  /** 일시 실패 결과를 기록한 시점부터 계산한 다음 재시도 시각을 반영한다. */
  void scheduleRetry(Instant nextAttemptAt) {
    this.claimToken = null;
    this.nextAttemptAt = nextAttemptAt;
  }

  void markSent() {
    this.status = DeliveryStatus.SENT.value();
    this.claimToken = null;
    this.sentAt = Instant.now();
    this.failureCategory = null;
  }

  void markFailed(String failureCategory) {
    this.status = DeliveryStatus.FAILED.value();
    this.claimToken = null;
    this.failureCategory = failureCategory;
  }

  /** 계정 간 오발송 방지 등 전송 대상에서 제외한다. */
  void cancel(String failureCategory) {
    this.status = DeliveryStatus.CANCELLED.value();
    this.claimToken = null;
    this.failureCategory = failureCategory;
  }
}
