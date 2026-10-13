package works.momens.server.project.core.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.core.ProjectHealthStatus;

/**
 * 라벨은 생성 시 한 번 발급하며 변경하지 않습니다.
 *
 * <p>입력된 {@code progress}는 저장하지만 조회 진행률은 태스크 상태로 계산합니다(ADR-0013). INSERT에 포함되는 상태 기본값은 DB DEFAULT
 * 대신 생성자에서 채웁니다.
 */
@Getter
@Entity
@Table(name = "projects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Project extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(nullable = false)
  private String name;

  @Column private String description;

  @Column(nullable = false)
  private String status;

  @Column(name = "owner_id", nullable = false, columnDefinition = "uuid")
  private UUID ownerId;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column private String summary;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(updatable = false)
  private String label;

  @Column(name = "health_status", nullable = false)
  private String healthStatus;

  @Column(nullable = false)
  private int progress;

  @Column(name = "unresolved_count", nullable = false)
  private int unresolvedCount;

  @Column(name = "voc_signal_count", nullable = false)
  private int vocSignalCount;

  @Column(name = "last_context_at")
  private Instant lastContextAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Builder
  private Project(
      UUID workspaceId,
      String name,
      String description,
      String status,
      UUID ownerId,
      LocalDate targetDate,
      String summary,
      String label,
      String healthStatus,
      int progress,
      int unresolvedCount,
      int vocSignalCount,
      Instant lastContextAt,
      Map<String, Object> metadata) {
    this.workspaceId = workspaceId;
    this.name = name;
    this.description = description;
    // INSERT 시 엔티티 값이 DB DEFAULT 보다 우선하므로, 레거시 기본값 'active'를 앱 생성에서도 보장한다.
    this.status = status != null ? status : "active";
    this.ownerId = ownerId;
    this.targetDate = targetDate;
    this.summary = summary;
    this.label = label;
    this.healthStatus = healthStatus != null ? healthStatus : ProjectHealthStatus.OPEN.value();
    this.progress = progress;
    this.unresolvedCount = unresolvedCount;
    this.vocSignalCount = vocSignalCount;
    this.lastContextAt = lastContextAt;
    this.metadata = metadata;
  }
}
