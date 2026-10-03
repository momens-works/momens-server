package works.momens.server.mobile.appupdate;

import java.util.EnumMap;
import java.util.Map;

/**
 * 앱 버전이 해당 플랫폼의 최소 지원 버전보다 낮은지 판정합니다.
 *
 * <p>모든 {@link AppPlatform}의 최소 지원 버전이 설정되어 있어야 생성할 수 있습니다. 플랫폼을 추가한 뒤 최소 지원 버전 설정을 누락하면 애플리케이션이
 * 시작되지 않습니다.
 */
final class AppUpdatePolicy {

  private final Map<AppPlatform, AppVersion> minimumVersions;

  AppUpdatePolicy(Map<AppPlatform, AppVersion> minimumVersions) {
    for (AppPlatform platform : AppPlatform.values()) {
      if (!minimumVersions.containsKey(platform)) {
        throw new IllegalArgumentException(
            "Minimum app version is not configured for " + platform.value());
      }
    }
    this.minimumVersions = new EnumMap<>(minimumVersions);
  }

  boolean isUpdateRequired(AppPlatform platform, AppVersion appVersion) {
    return appVersion.isOlderThan(minimumVersions.get(platform));
  }
}
