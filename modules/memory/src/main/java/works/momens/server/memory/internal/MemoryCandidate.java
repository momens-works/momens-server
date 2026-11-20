package works.momens.server.memory.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;

/**
 * 리뷰 상태 전이는 네이티브 SQL로 처리하므로 엔티티는 {@link Immutable}로 둡니다.
 *
 * <p>소프트 삭제 컬럼 없이 상태로 관리합니다. {@code status}는 DB CHECK로 제한하고, 제약이 없는 {@code candidate_type}은 worker
 * 값을 그대로 읽습니다.
 */
@Getter
@Entity
@Immutable
@Table(name = "memory_candidates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MemoryCandidate extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column private String label;

  @Column(name = "candidate_type", nullable = false)
  private String candidateType;

  @Column(nullable = false)
  private String title;

  @Column private String summary;

  @Column private String body;

  @Column private Double confidence;

  @Column private Double importance;

  @Column(nullable = false)
  private String status;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "source_ref_ids", columnDefinition = "uuid[]")
  private List<UUID> sourceRefIds;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "related_entity_ids", columnDefinition = "uuid[]")
  private List<UUID> relatedEntityIds;

  @Column(name = "proposed_by", nullable = false)
  private String proposedBy;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "reviewed_by_user_id", columnDefinition = "uuid")
  private UUID reviewedByUserId;

  @Column(name = "rejection_reason")
  private String rejectionReason;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;
}
