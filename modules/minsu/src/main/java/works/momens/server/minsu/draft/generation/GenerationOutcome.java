package works.momens.server.minsu.draft.generation;

/** 한 번의 생성 시도 결과. 동기 경로의 관측 태그와 비동기 경로의 재시도 판정에 사용한다. 재시도 여부와 종료 사유로의 매핑은 원장이 소유한다. */
public enum GenerationOutcome {
  DISABLED("fallback", "disabled"),
  /** 비동기 활성. 요청 경로에서는 고정 fallback만 돌려주고 실제 생성은 원장을 통해 scheduler가 맡는다. */
  DEFERRED("fallback", "async_deferred"),
  INVALID_CONFIG("fallback", "invalid_config"),
  INSUFFICIENT_CONTEXT("fallback", "insufficient_context"),
  TIMEOUT("fallback", "timeout"),
  PROVIDER_ERROR("fallback", "provider_error"),
  INVALID_RESPONSE("fallback", "invalid_response"),
  INVALID_OUTPUT("fallback", "invalid_output"),
  GENERATED_TITLE_FALLBACK("generated_title_fallback", "none"),
  GENERATED_TRUNCATED("generated_truncated", "none"),
  GENERATED("generated", "none");

  private final String outcome;
  private final String reason;

  GenerationOutcome(String outcome, String reason) {
    this.outcome = outcome;
    this.reason = reason;
  }

  String outcome() {
    return outcome;
  }

  /** 실패의 종류. 재시도 소진의 원인을 가르는 값이라 원장 쪽 계측도 읽는다. */
  public String reason() {
    return reason;
  }
}
