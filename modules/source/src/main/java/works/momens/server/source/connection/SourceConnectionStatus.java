package works.momens.server.source.connection;

/**
 * 외부 source 연동의 `status` 값입니다.
 *
 * <p>`source_connections.status`의 CHECK 제약에서 허용하는 값을 모두 표현합니다. 이 서버에서는 OAuth 승인이 완료되면 Figma 연동은
 * `PENDING`, 그 밖의 연동은 `ACTIVE`로 저장합니다.
 */
public enum SourceConnectionStatus {
  PENDING("PENDING"),
  ACTIVE("ACTIVE"),
  DISABLED("DISABLED"),
  ERROR("ERROR"),
  REVOKED("REVOKED");

  private final String value;

  SourceConnectionStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
