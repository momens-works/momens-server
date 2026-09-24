package works.momens.server.memory.internal;

/**
 * 확정 메모리의 `status` 값입니다.
 *
 * <p>`confirmed_memories.status`의 CHECK 제약에서 허용하는 값을 모두 표현합니다. 이 서버에서는 메모리를 확정할 때 `ACTIVE`, 다른 메모리로
 * resolved 처리할 때 `ARCHIVED`를 저장하며, 나머지 값은 레거시에서 저장합니다.
 */
enum ConfirmedMemoryStatus {
  ACTIVE("ACTIVE"),
  INVALIDATED("INVALIDATED"),
  ARCHIVED("ARCHIVED"),
  DELETED("DELETED");

  private final String value;

  ConfirmedMemoryStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
