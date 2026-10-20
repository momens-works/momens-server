package works.momens.server.project.milestone.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.milestone.MilestoneHealthStatus;

/** 워크스페이스는 소속 프로젝트에서 확인합니다. 프로젝트와 달리 진행률은 저장값을 사용합니다(ADR-0013). */
@Getter
@Entity
@Table(name = "milestones")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Milestone extends BaseEntity {

  @Version
  @Column(nullable = false)
  private long version;

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
      boolean descriptionSet,
      String status,
      LocalDate targetDate,
      boolean targetDateSet,
      String healthStatus,
      Integer progress,
      String summary,
      boolean summarySet) {
    if (name != null && !name.isEmpty()) {
      this.name = name;
    }
    if (descriptionSet) {
      this.description = description;
    }
    if (status != null && !status.isEmpty()) {
      this.status = status;
    }
    if (targetDateSet) {
      this.targetDate = targetDate;
    }
    if (healthStatus != null && !healthStatus.isEmpty()) {
      this.healthStatus = healthStatus;
    }
    if (progress != null) {
      this.progress = progress;
    }
    if (summarySet) {
      this.summary = summary;
    }
  }

  void delete() {
    this.deletedAt = Instant.now();
  }
}
