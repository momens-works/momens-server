package works.momens.server.memory.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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
 * 조회는 DTO projection, 후보 검토·해결은 네이티브 SQL, 새 행 생성은 엔티티를 사용합니다.
 *
 * <p>삭제 판정은 상태값이 아닌 {@code deleted_at}을 기준으로 합니다.
 */
@Getter
@Entity
@Table(name = "confirmed_memories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ConfirmedMemory extends BaseEntity {

  private static final String STATUS_ACTIVE = "ACTIVE";

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column private String label;

  @Column(name = "memory_type", nullable = false)
  private String memoryType;

  @Column(nullable = false)
  private String title;

  @Column private String summary;

  @Column private String body;

  @Column(nullable = false)
  private String status;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "source_ref_ids", columnDefinition = "uuid[]")
  private List<UUID> sourceRefIds;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "related_entity_ids", columnDefinition = "uuid[]")
  private List<UUID> relatedEntityIds;

  @Column(name = "created_from_candidate_id", columnDefinition = "uuid")
  private UUID createdFromCandidateId;

  @Column(name = "confirmed_by_user_id", columnDefinition = "uuid")
  private UUID confirmedByUserId;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "valid_from")
  private Instant validFrom;

  @Column(name = "valid_until")
  private Instant validUntil;

  @Column(name = "invalidated_at")
  private Instant invalidatedAt;

  @Column(name = "invalidated_by_user_id", columnDefinition = "uuid")
  private UUID invalidatedByUserId;

  @Column(name = "invalidation_reason")
  private String invalidationReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Builder
  private ConfirmedMemory(
      UUID workspaceId,
      String label,
      String memoryType,
      String title,
      String body,
      List<UUID> relatedEntityIds,
      UUID confirmedByUserId,
      Instant confirmedAt,
      Instant validFrom,
      Map<String, Object> metadata) {
    this.workspaceId = workspaceId;
    this.label = label;
    this.memoryType = memoryType;
    this.title = title;
    this.body = body;
    this.status = STATUS_ACTIVE;
    this.relatedEntityIds = relatedEntityIds;
    this.confirmedByUserId = confirmedByUserId;
    this.confirmedAt = confirmedAt;
    this.validFrom = validFrom;
    this.metadata = metadata;
  }
}
