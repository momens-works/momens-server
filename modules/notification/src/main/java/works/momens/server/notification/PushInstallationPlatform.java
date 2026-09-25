package works.momens.server.notification;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * push 설치를 등록한 기기의 플랫폼입니다.
 *
 * <p>`push_installations.platform`의 CHECK 제약에서 허용하는 값을 표현합니다. 현재는 Android만 허용합니다. 모바일의 push 기기 등록
 * 요청 DTO에서 이 enum 타입으로 platform 값을 받습니다.
 */
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
