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
 * 프로젝트.
 *
 * <p>레거시 {@code momens-api}의 {@code projects} 테이블과 호환됩니다.
 *
 * <p>{@code label}, {@code healthStatus}, {@code progress}, {@code unresolvedCount}, {@code
 * vocSignalCount}, {@code lastContextAt}, {@code metadata}는 웹 이관 작업(MOM-0857)에서 추가한 레거시 컬럼입니다. 프로젝트
 * 생성 작업(MOM-0866)이 추가되면서 읽기 전용 매핑을 해제했습니다. 생성 요청으로 전달받은 값을 레거시와 동일하게 저장해야 어느 서버가 요청을 처리하더라도 같은 행이
 * 생성됩니다. 조회는 계속 이 엔티티가 아닌 {@link works.momens.server.project.core.ProjectDetailReader}가 담당합니다.
 * {@code metadata}는 워크스페이스 생성(MOM-0897)에서 값을 저장하기 시작하면서 생성자에 추가했습니다. 다른 생성 경로에서는 값을 전달하지 않으므로 NULL로
 * 저장됩니다.
 *
 * <p>{@code label}에만 {@code updatable = false}를 적용합니다. 라벨은 프로젝트를 생성할 때 한 번 발급하며 이후에는 변경하지 않습니다. 나머지
 * 컬럼은 매핑으로 변경을 차단하는 대신 이 클래스에 수정 메서드를 제공하지 않는 방식으로 보호합니다.
 *
 * <p>{@code progress}는 저장만 하며 신규 서버의 조회에는 사용하지 않습니다. 진행률은 태스크를 기준으로 계산하므로(MOM-0800, ADR-0013) DB에
 * 저장된 값을 조회 결과에 사용하지 않습니다. 생성 API가 입력받기로 한 값을 조용히 버리지 않도록 저장을 위한 매핑은 유지합니다.
 *
 * <p>{@code status}와 {@code healthStatus}의 기본값은 생성자에서 설정합니다. INSERT 문에 값을 포함하면 DB DEFAULT가 적용되지
 * 않으므로, 입력값이 없을 때 레거시가 사용하는 {@code active}와 {@code open}을 애플리케이션에서도 동일하게 보장합니다.
 *
 * <p>{@code status}는 base persistence 단계라 문자열로만 둡니다. DB CHECK 제약이 {@code active}/{@code archived}만
 * 허용합니다.
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
    this.status = status != null ? status : ProjectStatus.ACTIVE.value();
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
