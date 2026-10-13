package works.momens.server.context.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 연결 쓰기는 전용 쿼리를 사용하므로 이 매핑은 {@link Immutable}로 둡니다.
 *
 * <p>태스크 연결 조회에 필요한 컬럼만 매핑하며, 매핑하지 않은 컬럼은 {@code ddl-auto=validate}가 검증하지 않습니다.
 */
@Entity
@Immutable
@Table(name = "entity_relations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class EntityRelation {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "from_entity_type", nullable = false)
  private String fromEntityType;

  @Column(name = "from_entity_id", nullable = false, columnDefinition = "uuid")
  private UUID fromEntityId;

  @Column(name = "relation_type", nullable = false)
  private String relationType;

  @Column(name = "to_entity_type", nullable = false)
  private String toEntityType;

  @Column(name = "to_entity_id", nullable = false, columnDefinition = "uuid")
  private UUID toEntityId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
