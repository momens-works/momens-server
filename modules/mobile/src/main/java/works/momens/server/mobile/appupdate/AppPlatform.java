package works.momens.server.mobile.appupdate;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 앱 업데이트 필요 여부를 확인할 때 앱이 실행되는 플랫폼을 나타냅니다. 쿼리 파라미터에서는 {@link #value()} 값({@code android}, {@code
 * ios})으로 받습니다.
 *
 * <p>push 설치 플랫폼을 나타내는 {@code PushInstallationPlatform}은 {@code push_installations.platform}의 CHECK
 * 제약과 연결되어 있고 허용 값도 다르므로 별도로 정의합니다.
 */
enum AppPlatform {
  ANDROID("android"),
  IOS("ios");

  private final String value;

  AppPlatform(String value) {
    this.value = value;
  }

  @JsonValue
  String value() {
    return value;
  }
}
