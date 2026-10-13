package works.momens.server.source.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * {@code captures_read_count}와 {@code candidates_extracted_count}는 worker가 갱신하므로 이 엔티티에서는 조회만 합니다.
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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  private SourceConnectionStatus status;

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
    this.status = status;
    this.externalWorkspaceId = externalWorkspaceId;
    this.externalWorkspaceName = externalWorkspaceName;
    this.connectedByUserId = connectedByUserId;
    this.connectedAt = connectedAt;
    this.metadata = metadata;
  }
}
