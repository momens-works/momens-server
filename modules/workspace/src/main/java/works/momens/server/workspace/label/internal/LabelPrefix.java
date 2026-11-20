package works.momens.server.workspace.label.internal;

/**
 * DB CHECK와 같은 값 집합을 유지하므로 서버가 발급하지 않는 worker 후보용 {@code SUG}도 포함합니다.
 *
 * <p>상수 이름 변경이 저장 값을 바꾸지 않도록 {@code value}를 별도로 둡니다.
 */
enum LabelPrefix {
  SUG("SUG"),
  MEM("MEM"),
  MOM("MOM"),
  PRJ("PRJ");

  private final String value;

  LabelPrefix(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
