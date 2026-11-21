package works.momens.server.source.connection;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * {@code source_connections} 테이블의 조회와 저장을 담당합니다.
 *
 * <p>{@code findByWorkspaceIdOrderByCreatedAtDesc}의 정렬 기준은 레거시의 연결 목록 조회 쿼리와 같습니다.
 *
 * <p>{@code findForReconnect}는 기존 연결이 다시 승인되었을 때 갱신할 대상을 행 잠금과 함께 조회합니다. 워크스페이스·source type·외부 ID
 * 조합에 UNIQUE 제약이 없어 여러 행이 조회될 수 있으므로, 잠금 순서가 항상 같도록 생성 시각과 id 순으로 정렬합니다.
 */
public interface SourceConnectionRepository extends JpaRepository<SourceConnection, UUID> {

  @Query("select c.workspaceId from SourceConnection c where c.id = :connectionId")
  Optional<UUID> findWorkspaceId(UUID connectionId);

  /** 요청마다 DB에서 증가하는 값을 발급합니다. Worker의 완료 시각과 비교하지 않는 요청 경계입니다. */
  @Modifying
  @Query(
      value =
          "UPDATE source_connections"
              + " SET resync_requested_at = greatest(statement_timestamp(), resync_requested_at + interval '1 microsecond'),"
              + " updated_at = greatest(updated_at, statement_timestamp(), resync_requested_at + interval '1 microsecond')"
              + " WHERE id = :connectionId AND workspace_id = :workspaceId",
      nativeQuery = true)
  int requestResync(UUID connectionId, UUID workspaceId);

  /** 재승인에서 소유하는 컬럼만 갱신하며, 응답 재조회가 최신 값을 읽도록 영속성 컨텍스트를 비웁니다. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SourceConnection c set c.status = :status,"
          + " c.externalWorkspaceName = :externalWorkspaceName, c.connectedByUserId = :connectedByUserId,"
          + " c.connectedAt = :connectedAt, c.metadata = :metadata, c.disabledAt = null,"
          + " c.updatedAt = greatest(c.updatedAt, :connectedAt) where c.id = :connectionId")
  int reconnect(
      UUID connectionId,
      SourceConnectionStatus status,
      String externalWorkspaceName,
      UUID connectedByUserId,
      Instant connectedAt,
      Map<String, Object> metadata);

  /** 활성화 소유 컬럼만 갱신하고 worker 통계는 보존합니다. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SourceConnection c set c.metadata = :metadata, c.status = :status,"
          + " c.disabledAt = null, c.updatedAt = greatest(c.updatedAt, :now) where c.id = :id")
  void configureFigma(
      UUID id, Map<String, Object> metadata, SourceConnectionStatus status, Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from SourceConnection c where c.id = :id and c.workspaceId = :workspaceId")
  Optional<SourceConnection> findForUpdate(UUID id, UUID workspaceId);

  /** worker 소유 통계·동기화 시각과 metadata를 덮어쓰지 않습니다. */
  @Modifying
  @Query(
      "update SourceConnection c set c.status = :status, c.disabledAt = :now,"
          + " c.updatedAt = :now where c.id = :id")
  void disable(UUID id, SourceConnectionStatus status, Instant now);

  List<SourceConnection> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select c from SourceConnection c where c.workspaceId = :workspaceId"
          + " and c.sourceType = :sourceType and c.externalWorkspaceId = :externalId"
          + " order by c.createdAt, c.id")
  List<SourceConnection> findForReconnect(UUID workspaceId, String sourceType, String externalId);

  /**
   * credential 갱신이 {@link SourceCredentialRepository#findFigmaByUser}와 같은 순서로 행을 잠가 교착을 피하도록 id 순으로
   * 반환합니다.
   */
  List<SourceConnection> findBySourceTypeAndExternalWorkspaceIdOrderById(
      String sourceType, String externalWorkspaceId);
}
