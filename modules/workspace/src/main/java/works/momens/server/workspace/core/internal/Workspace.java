package works.momens.server.workspace.core.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

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
