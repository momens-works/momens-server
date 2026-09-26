package works.momens.server.signal.action;

/**
 * {@code signal_actions.action_type} 값.
 *
 * <p>DB CHECK 제약(local/test 미러 {@code V20260707110000})과 응답 계약({@code
 * SignalActionResult.actionType}, 문서화된 {@code action} enum)이 이 값을 그대로 쓴다. {@code
 * mobile.board.MobileTaskStatus}가 저장/응답 값을 enum 상수에 묶어 쓰는 것과 같은 방식으로, {@code
 * SignalActionServiceImpl}·{@code SignalActionExecutor}가 문자열 리터럴을 각자 들고 있지 않게 한다.
 */
enum SignalActionType {
  CONVERT_TO_TASK("convert_to_task"),
  DISMISS("dismiss");

  private final String value;

  SignalActionType(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
