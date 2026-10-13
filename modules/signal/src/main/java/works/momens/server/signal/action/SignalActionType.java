package works.momens.server.signal.action;

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
