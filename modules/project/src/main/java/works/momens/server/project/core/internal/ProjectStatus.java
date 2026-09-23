package works.momens.server.project.core.internal;

/**
 * 프로젝트의 `status` 값입니다.
 *
 * <p>`projects.status`의 CHECK 제약에서 허용하는 값을 모두 표현합니다. 이 서버에서는 프로젝트를 생성할 때 `ACTIVE`만 저장하며,
 * `ARCHIVED`는 레거시에서 저장합니다.
 */
enum ProjectStatus {
  ACTIVE("active"),
  ARCHIVED("archived");

  private final String value;

  ProjectStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
