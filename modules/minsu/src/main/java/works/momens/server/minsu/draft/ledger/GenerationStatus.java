package works.momens.server.minsu.draft.ledger;

/**
 * 원장 상태.
 *
 * <p>진행 여부만 표현하고 종료 사유는 {@code completion_reason} 컬럼이 따로 갖는다({@link CompletionReason}). API가 노출하는
 * 값은 {@code generating}과 {@code ready} 둘뿐이며 모든 {@link #COMPLETED}는 사유와 무관하게 {@code ready}로 매핑된다.
 */
enum GenerationStatus {
  /** 적재됨 또는 재시도 대기. */
  PENDING("pending"),
  /** claim 보유, 생성 중. */
  PROCESSING("processing"),
  /** 종료. 사유는 {@code completion_reason}. */
  COMPLETED("completed");

  private final String value;

  GenerationStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
