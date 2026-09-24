package works.momens.server.source.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;

/**
 * 워크스페이스와 외부 provider 간의 연결을 나타냅니다.
 *
 * <p>레거시 {@code momens-api}가 소유하는 {@code source_connections} 테이블과 호환됩니다. 신규 서버에서도 쓰기 작업을 수행하므로 단일
 * UUID 기본 키와 감사 필드를 제공하는 {@link BaseEntity}를 상속합니다. 같은 조건을 가진 {@code :workspace} 모듈의 {@code
 * Workspace}와 동일한 방식입니다.
 *
 * <p>{@code captures_read_count}와 {@code candidates_extracted_count}는 {@code momens-worker}가 갱신합니다.
 * 이 엔티티는 두 값을 조회만 하며 변경하지 않습니다.
 */
@Getter
@Entity
@Table(name = "source_connections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceConnection extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "source_type", nullable = false)
  private String sourceType;

  @Column(nullable = false)
  private String status;

  @Column(name = "external_workspace_id")
  private String externalWorkspaceId;

  @Column(name = "external_workspace_name")
  private String externalWorkspaceName;

  @Column(name = "connected_by_user_id", columnDefinition = "uuid")
  private UUID connectedByUserId;

  @Column(name = "connected_at")
  private Instant connectedAt;

  @Column(name = "last_synced_at")
  private Instant lastSyncedAt;

  @Column(name = "disabled_at")
  private Instant disabledAt;

  @Column(name = "resync_requested_at")
  private Instant resyncRequestedAt;

  @Column(name = "captures_read_count", nullable = false)
  private long capturesReadCount;

  @Column(name = "candidates_extracted_count", nullable = false)
  private long candidatesExtractedCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Builder
  SourceConnection(
      UUID workspaceId,
      String sourceType,
      SourceConnectionStatus status,
      String externalWorkspaceId,
      String externalWorkspaceName,
      UUID connectedByUserId,
      Instant connectedAt,
      Map<String, Object> metadata) {
    this.workspaceId = workspaceId;
    this.sourceType = sourceType;
    this.status = status.value();
    this.externalWorkspaceId = externalWorkspaceId;
    this.externalWorkspaceName = externalWorkspaceName;
    this.connectedByUserId = connectedByUserId;
    this.connectedAt = connectedAt;
    this.metadata = metadata;
  }

  /**
   * 기존 연결이 다시 승인되면 연결 상태, 이름, 승인자, 승인 시각을 갱신하고 {@code disabled_at}을 초기화합니다.
   *
   * <p>레거시의 연결 갱신 경로와 동일한 동작입니다. 비활성화된 연결도 다시 승인되면 활성 상태로 전환됩니다.
   */
  public void reconnect(
      SourceConnectionStatus status,
      String externalWorkspaceName,
      UUID connectedByUserId,
      Instant connectedAt,
      Map<String, Object> metadata) {
    this.status = status.value();
    this.externalWorkspaceName = externalWorkspaceName;
    this.connectedByUserId = connectedByUserId;
    this.connectedAt = connectedAt;
    this.metadata = metadata;
    this.disabledAt = null;
  }
}
