package works.momens.server.notification;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * push 설치를 등록한 기기의 플랫폼입니다.
 *
 * <p>`push_installations.platform`의 CHECK 제약에서 허용하는 값을 표현합니다. 현재는 Android만 허용합니다. 등록 요청으로 받은
 * platform 값은 `PushDeviceRegistrar` 구현에서 이 enum을 기준으로 검증합니다.
 */
public enum PushInstallationPlatform {
  ANDROID("android");

  private final String value;

  PushInstallationPlatform(String value) {
    this.value = value;
  }

  public static Optional<PushInstallationPlatform> from(String value) {
    for (PushInstallationPlatform platform : values()) {
      if (platform.value.equals(value)) {
        return Optional.of(platform);
      }
    }
    return Optional.empty();
  }

  @JsonValue
  public String value() {
    return value;
  }
}
