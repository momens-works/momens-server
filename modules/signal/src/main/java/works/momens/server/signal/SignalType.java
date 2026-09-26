package works.momens.server.signal;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Signal의 종류입니다.
 *
 * <p>`signals.type`의 CHECK 제약에서 허용하는 네 가지 값을 표현합니다. dev 데모용 Signal 생성 요청 DTO에서 이 enum 타입으로 type 값을
 * 받습니다.
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

  @JsonValue
  public String value() {
    return value;
  }
}
