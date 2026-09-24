package works.momens.server.signal;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * Signal의 종류입니다.
 *
 * <p>`signals.type`의 CHECK 제약에서 허용하는 네 가지 값을 표현합니다. dev 데모용 Signal 생성 요청의 type 값은
 * `DevSignalWriter`에서 이 enum을 기준으로 검증합니다.
 */
public enum SignalType {
  DECISION("decision"),
  RISK("risk"),
  QUESTION("question"),
  CHANGE("change");

  private final String value;

  SignalType(String value) {
    this.value = value;
  }

  public static Optional<SignalType> from(String value) {
    for (SignalType constant : values()) {
      if (constant.value.equals(value)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return value;
  }
}
