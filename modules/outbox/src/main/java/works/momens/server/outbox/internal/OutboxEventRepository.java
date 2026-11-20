package works.momens.server.outbox.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

  @Query(
      value =
          """
          WITH first_fresh AS (
              SELECT MIN(id) AS id
              FROM outbox_events
              WHERE id > :afterId
                AND created_at > NOW() - (:safetyLagMillis * INTERVAL '1 millisecond')
          )
          SELECT event.*
          FROM outbox_events event
          WHERE event.id > :afterId
            AND ((SELECT id FROM first_fresh) IS NULL
              OR event.id < (SELECT id FROM first_fresh))
          ORDER BY event.id ASC
          LIMIT :limit
          """,
      nativeQuery = true)
  List<OutboxEvent> findDuePrefixAfter(
      @Param("afterId") long afterId,
      @Param("safetyLagMillis") long safetyLagMillis,
      @Param("limit") int limit);

  @Query(
      value =
          """
          WITH first_fresh AS (
              SELECT MIN(id) AS id
              FROM outbox_events
              WHERE created_at > NOW() - (:safetyLagMillis * INTERVAL '1 millisecond')
          )
          SELECT COALESCE(MAX(event.id), 0)
          FROM outbox_events event
          WHERE (SELECT id FROM first_fresh) IS NULL
             OR event.id < (SELECT id FROM first_fresh)
          """,
      nativeQuery = true)
  long findLatestIdBeforeFreshPrefix(@Param("safetyLagMillis") long safetyLagMillis);

  /** 멱등키 충돌은 실패가 아닌 무시로 처리해야 하므로 native INSERT의 {@code ON CONFLICT DO NOTHING}을 사용합니다. */
  @Modifying
  @Query(
      value =
          "INSERT INTO outbox_events "
              + "(issued_by, workspace_id, aggregate_type, aggregate_id, event_type, payload, "
              + "idempotency_key, created_at) "
              + "VALUES (:issuedBy, :workspaceId, :aggregateType, :aggregateId, :eventType, "
              + "CAST(:payload AS jsonb), :idempotencyKey, NOW()) "
              + "ON CONFLICT (idempotency_key) DO NOTHING",
      nativeQuery = true)
  void insertIgnoringConflict(
      @Param("issuedBy") String issuedBy,
      @Param("workspaceId") UUID workspaceId,
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") String aggregateId,
      @Param("eventType") String eventType,
      @Param("payload") String payload,
      @Param("idempotencyKey") String idempotencyKey);
}
