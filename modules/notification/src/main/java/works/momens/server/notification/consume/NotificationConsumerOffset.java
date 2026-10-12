package works.momens.server.notification.consume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * notification consumer의 outbox polling watermark.
 *
 * <p>consumer 이름이 자연키인 단일 상태 행이라 공유 베이스 엔티티(UUID PK)를 상속하지 않는다. 행 생성은 native {@code ON CONFLICT DO
 * NOTHING} 시드가 담당하고, JPA는 잠금 조회와 전진(update)만 다룬다.
 */
@Getter
@Entity
@Table(name = "notification_consumer_offsets")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationConsumerOffset {

  @Id
  @Column(name = "consumer_name")
  private String consumerName;

  @Column(name = "last_outbox_id", nullable = false)
  private long lastOutboxId;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  void advanceTo(long outboxId) {
    this.lastOutboxId = outboxId;
  }
}
