package works.momens.server.source.ref;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 사용자가 붙여넣은 링크는 엔티티로 저장하므로 읽기 전용으로 선언하지 않습니다.
 *
 * <p>감사 컬럼은 INSERT에 포함되므로 앱에서 채워야 합니다. null을 보내면 DB 기본값이 적용되지 않습니다. {@code metadata}는 빈 객체로 저장하며
 * 응답에서는 제외합니다.
 */
@Getter
@Entity
@Table(name = "source_refs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class SourceRef extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "source_type", nullable = false)
  private String sourceType;

  @Column(name = "source_object_type", nullable = false)
  private String sourceObjectType;

  @Column(name = "source_object_id", nullable = false)
  private String sourceObjectId;

  @Column private String title;

  @Column private String snippet;

  @Column(name = "text")
  private String text;

  @Column(name = "source_url")
  private String sourceUrl;

  @Column(name = "source_created_at")
  private Instant sourceCreatedAt;

  @Column(name = "author_name")
  private String authorName;

  @Column(name = "author_email")
  private String authorEmail;

  @Column(nullable = false)
  private String visibility;

  @Column(name = "permission_key")
  private String permissionKey;

  @Column(name = "verified_by_user_id", columnDefinition = "uuid")
  private UUID verifiedByUserId;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Builder
  private SourceRef(
      UUID workspaceId,
      String sourceType,
      String sourceObjectType,
      String sourceObjectId,
      String title,
      String sourceUrl,
      String visibility,
      Map<String, Object> metadata) {
    this.workspaceId = workspaceId;
    this.sourceType = sourceType;
    this.sourceObjectType = sourceObjectType;
    this.sourceObjectId = sourceObjectId;
    this.title = title;
    this.sourceUrl = sourceUrl;
    this.visibility = visibility;
    this.metadata = metadata;
  }
}
