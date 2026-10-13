package works.momens.server.notification;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PushInstallationPlatform {
  ANDROID("android");

  private final String value;

  PushInstallationPlatform(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
