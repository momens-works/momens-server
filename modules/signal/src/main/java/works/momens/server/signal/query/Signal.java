package works.momens.server.signal.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * worker가 생산하는 Signal의 조회 모델입니다. 사용자 처리 이력은 별도 {@code signal_actions}가 소유합니다.
 *
 * <p>식별자·생성 시각은 생산자가 정하며, 매핑하지 않는 {@code metadata}·{@code updated_at}은 스키마 검증 대상이 아닙니다.
 */
@Getter
@Entity
@Immutable
@Table(name = "signals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Signal {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
  private UUID projectId;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String description;

  @Column private String impact;

  @Column(name = "minsu_suggestion")
  private String minsuSuggestion;

  @Column(name = "occurred_at")
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
