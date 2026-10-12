package works.momens.server.notification.consume;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationConsumerOffsetRepository
    extends JpaRepository<NotificationConsumerOffset, String> {

  /** consumer 상태 행을 잠가 여러 인스턴스가 같은 outbox 구간을 동시에 materialize하지 않게 한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from NotificationConsumerOffset o where o.consumerName = :consumerName")
  Optional<NotificationConsumerOffset> lockByName(@Param("consumerName") String consumerName);

  /** 최초 watermark 시드. 동시 시드 경합은 PK 충돌 무시로 한쪽만 반영한다. */
  @Modifying
  @Query(
      value =
          "INSERT INTO notification_consumer_offsets (consumer_name, last_outbox_id, created_at, updated_at) "
              + "VALUES (:consumerName, :lastOutboxId, NOW(), NOW()) "
              + "ON CONFLICT (consumer_name) DO NOTHING",
      nativeQuery = true)
  void seedIgnoringConflict(
      @Param("consumerName") String consumerName, @Param("lastOutboxId") long lastOutboxId);
}
