package works.momens.server.workspace.label.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * workspace별 라벨 발급 카운터.
 *
 * <p>레거시 {@code momens-api}의 {@code workspace_label_sequences} 테이블과 호환됩니다. {@code (workspace_id,
 * label_prefix)} 복합 PK라 단일 UUID PK용 {@link works.momens.server.common.persistence.BaseEntity}를 상속하지
 * 않고, 식별자는 {@link WorkspaceLabelSequenceId}로 둡니다.
 *
 * <p>이 엔티티는 테이블 스키마를 매핑해 {@code ddl-auto: validate}와 조회(테스트)에 쓰입니다. 쓰기는 JPA {@code save()}가 아니라
 * {@link WorkspaceLabelSequenceRepository#allocate}의 {@code ON CONFLICT} UPSERT로만 합니다. JPA는 {@code
 * ON CONFLICT}를 표현하지 못하고, 발급은 동시 호출에도 행 잠금으로 직렬화되는 단일 문장이어야 하기 때문입니다. 그래서 {@code next_value}/{@code
 * updated_at} 값은 JPA Auditing 없이 DB가 직접 넣습니다. 행을 새로 넣을 때는 스키마 기본값으로, 갱신할 때는 쿼리가 {@code now()}로 다시
 * 씁니다.
 */
@Getter
@Entity
@Table(name = "workspace_label_sequences")
@IdClass(WorkspaceLabelSequenceId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WorkspaceLabelSequence {

  @Id
  @Column(name = "workspace_id", columnDefinition = "uuid")
  private UUID workspaceId;

  @Id
  @Column(name = "label_prefix")
  private String labelPrefix;

  @Column(name = "next_value", nullable = false)
  private Long nextValue;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
