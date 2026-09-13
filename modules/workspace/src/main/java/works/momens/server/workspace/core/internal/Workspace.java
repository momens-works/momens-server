package works.momens.server.workspace.core.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

/**
 * 워크스페이스.
 *
 * <p>레거시 {@code momens-api}의 {@code workspaces} 테이블과 호환됩니다. 식별자와 감사 필드는 {@link BaseEntity}가 담당합니다.
 *
 * <p>엔티티가 cross-module API로 노출되지 않도록 모듈 {@code internal} 패키지에 package-private로 둡니다. 다른 모듈은
 * workspace 모듈의 public API만 사용합니다.
 */
@Getter
@Entity
@Table(name = "workspaces")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Workspace extends BaseEntity {

  @Column(nullable = false)
  private String name;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column private String description;

  @Builder
  private Workspace(String name, String slug, String description) {
    this.name = name;
    this.slug = slug;
    this.description = description;
  }

  /** null이 아닌 값만 반영합니다. 기존 값을 유지할 필드는 호출 측에서 null로 전달합니다. */
  void update(String name, String description, String slug) {
    if (name != null) {
      this.name = name;
    }
    if (description != null) {
      this.description = description;
    }
    if (slug != null) {
      this.slug = slug;
    }
  }
}
