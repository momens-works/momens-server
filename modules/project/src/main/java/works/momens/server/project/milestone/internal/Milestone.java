package works.momens.server.project.milestone.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.milestone.MilestoneHealthStatus;

/**
 * 마일스톤.
 *
 * <p>레거시 {@code momens-api}의 {@code milestones} 테이블과 호환됩니다.
 *
 * <p>마일스톤 생성 작업(MOM-0866)이 추가되면서 이 엔티티에 쓰기 경로가 생겼습니다. 이전에는 쓰기 경로가 없어 엔티티 전체를 읽기 전용으로 선언했지만, 해당 상태에서
 * 수정 메서드를 추가하면 UPDATE가 예외 없이 무시되어 문제를 늦게 발견할 수 있습니다. 조회는 계속 이 엔티티가 아닌 {@link
 * works.momens.server.project.milestone.MilestoneReader}가 담당합니다. 엔티티 매핑은 운영 환경의 {@code
 * ddl-auto=validate}가 공유 스키마와의 불일치를 서버 기동 시점에 검출할 수 있도록 유지합니다.
 *
 * <p>{@code workspace_id}가 없습니다. 워크스페이스는 {@code project_id}를 거쳐서만 알 수 있어 조회 쿼리가 {@code projects}를
 * 조인합니다. {@code label}과 {@code metadata}도 없어 project와 필드 집합이 다릅니다.
 *
 * <p>{@code progress}는 project와 달리 매핑합니다. 레거시 write 경로가 실제로 유지하는 값이고 웹이 그대로 읽습니다. 저장값을 쓰지 않기로 한
 * 결정(ADR-0013)은 {@code projects.progress}에만 해당합니다.
 *
 * <p>{@code status}와 {@code health_status}는 base persistence 단계라 문자열로만 둡니다. DB CHECK 제약이 허용값을
 * 강제합니다.
 *
 * <p>{@code status}와 {@code healthStatus}의 기본값은 생성자에서 모두 {@code planned}로 설정합니다. 같은 이름을 사용하는 프로젝트
 * 컬럼과는 기본값이 다릅니다.
 */
@Getter
@Entity
@Table(name = "milestones")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Milestone extends BaseEntity {

  @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
  private UUID projectId;

  @Column(nullable = false)
  private String name;

  @Column private String description;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column(nullable = false)
  private String status;

  @Column(name = "health_status", nullable = false)
  private String healthStatus;

  @Column(nullable = false)
  private int progress;

  @Column private String summary;

  @Column(name = "last_context_at")
  private Instant lastContextAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Builder
  private Milestone(
      UUID projectId,
      String name,
      String description,
      LocalDate targetDate,
      String status,
      String healthStatus,
      int progress,
      String summary,
      Instant lastContextAt) {
    this.projectId = projectId;
    this.name = name;
    this.description = description;
    this.targetDate = targetDate;
    this.status = status != null ? status : MilestoneStatus.PLANNED.value();
    this.healthStatus = healthStatus != null ? healthStatus : MilestoneHealthStatus.PLANNED.value();
    this.progress = progress;
    this.summary = summary;
    this.lastContextAt = lastContextAt;
  }

  void update(
      String name,
      String description,
      String status,
      LocalDate targetDate,
      String healthStatus,
      Integer progress,
      String summary) {
    if (name != null && !name.isEmpty()) {
      this.name = name;
    }
    if (description != null && !description.isEmpty()) {
      this.description = description;
    }
    if (status != null && !status.isEmpty()) {
      this.status = status;
    }
    if (targetDate != null) {
      this.targetDate = targetDate;
    }
    if (healthStatus != null && !healthStatus.isEmpty()) {
      this.healthStatus = healthStatus;
    }
    if (progress != null) {
      this.progress = progress;
    }
    if (summary != null && !summary.isEmpty()) {
      this.summary = summary;
    }
  }

  void delete() {
    this.deletedAt = Instant.now();
  }
}
