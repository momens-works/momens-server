package works.momens.server.notification.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PushDeliveryRepository extends JpaRepository<PushDelivery, PushDeliveryId> {

  @Query(value = "SELECT NOW()", nativeQuery = true)
  Instant currentDatabaseTime();

  /**
   * 동일 event의 기기별 delivery는 복합 PK로 중복을 막는다(멱등 materialization). JPA {@code save()}는 이 시맨틱을 표현할 수 없어
   * native insert로 직접 {@code ON CONFLICT DO NOTHING}을 건다(outbox appender와 같은 방식).
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO push_deliveries "
              + "(outbox_event_id, installation_id, target_user_id, status, attempt_count, "
              + "next_attempt_at, created_at, updated_at) "
              + "VALUES (:outboxEventId, :installationId, :targetUserId, :pendingStatus, 0, NOW(), "
              + "NOW(), NOW()) "
              + "ON CONFLICT (outbox_event_id, installation_id) DO NOTHING",
      nativeQuery = true)
  void insertPendingIgnoringConflict(
      @Param("outboxEventId") long outboxEventId,
      @Param("installationId") UUID installationId,
      @Param("targetUserId") UUID targetUserId,
      @Param("pendingStatus") String pendingStatus);

  /**
   * 재시도 시각이 지난 pending delivery를 {@code FOR UPDATE SKIP LOCKED}로 클레임해 여러 인스턴스가 같은 delivery를 이중 발송하지
   * 않게 한다(10.3절).
   */
  // PostgreSQL이 인덱스를 사용하려면 WHERE의 status 조건이 부분 인덱스 조건과 같아야 하므로 리터럴로 유지합니다.
  @Query(
      value =
          "SELECT * FROM push_deliveries "
              + "WHERE status = 'pending' AND next_attempt_at <= NOW() "
              + "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<PushDelivery> lockDue(@Param("limit") int limit);

  @Query(
      value =
          "SELECT * FROM push_deliveries WHERE outbox_event_id = :outboxEventId "
              + "AND installation_id = :installationId FOR UPDATE",
      nativeQuery = true)
  Optional<PushDelivery> lockById(
      @Param("outboxEventId") long outboxEventId, @Param("installationId") UUID installationId);
}
