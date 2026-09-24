package works.momens.server.outbox.internal;

/**
 * outbox event를 발행한 서버입니다.
 *
 * <p>`outbox_events.issued_by`의 CHECK 제약에서 허용하는 값을 표현합니다. 이 서버에서 발행하는 event에는 항상 `API_SERVER`를
 * 저장합니다.
 */
enum OutboxEventIssuer {
  WORKER("worker"),
  API_SERVER("api-server");

  private final String value;

  OutboxEventIssuer(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
