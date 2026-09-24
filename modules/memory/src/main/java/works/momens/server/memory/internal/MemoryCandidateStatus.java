package works.momens.server.memory.internal;

/**
 * 메모리 후보의 `status` 값입니다.
 *
 * <p>`memory_candidates.status`의 CHECK 제약에서 허용하는 값을 모두 표현합니다. 이 서버에서는 `PROPOSED` 상태인 후보만 review하며,
 * review 결과에 따라 나머지 네 값 중 하나로 변경합니다.
 */
enum MemoryCandidateStatus {
  PROPOSED("PROPOSED"),
  CONFIRMED("CONFIRMED"),
  REJECTED("REJECTED"),
  MERGED("MERGED"),
  EXPIRED("EXPIRED");

  private final String value;

  MemoryCandidateStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
