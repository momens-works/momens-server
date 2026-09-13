package works.momens.server.workspace.label.internal;

/**
 * 워크스페이스 라벨에 사용하는 접두사입니다.
 *
 * <p>저장 가능한 값은 {@code workspace_label_sequences.label_prefix}의 {@code CHECK} 제약에서 허용하는 네 가지 값과
 * 동일합니다. 접두사를 추가하거나 제거할 때는 마이그레이션과 이 {@code enum}을 함께 변경해야 합니다.
 *
 * <p>{@code SUG}는 worker가 제안하는 메모리 후보에 사용하는 접두사이므로 이 서버에서는 발급하지 않습니다. 다만 후보를 조회할 때 해당 라벨을 읽어야 하고,
 * {@code CHECK} 제약과 값 집합을 동일하게 유지해야 한쪽이 변경되었을 때 차이를 확인할 수 있으므로 이 {@code enum}에 포함합니다.
 *
 * <p>상수 이름과 저장 값이 같더라도 {@code value}를 별도로 둡니다. {@code name()}을 저장 값으로 사용하면 상수 이름을 변경할 때 저장 값까지 함께
 * 바뀌기 때문입니다.
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
