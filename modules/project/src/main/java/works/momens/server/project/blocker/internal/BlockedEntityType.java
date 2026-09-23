package works.momens.server.project.blocker.internal;

/**
 * 블로커가 가리키는 대상의 종류입니다.
 *
 * <p>`blockers` 테이블은 레거시가 소유하며 이 서버에서는 읽기만 합니다. CHECK 제약으로 값을 제한하는 컬럼은 모두 도메인 enum과 연결한다는 기준에 따라
 * `blockers.blocked_entity_type`에서 허용하는 값의 집합을 표현합니다.
 */
enum BlockedEntityType {
  TASK("task"),
  MILESTONE("milestone");

  private final String value;

  BlockedEntityType(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
