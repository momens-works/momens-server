package works.momens.server.mobile.appupdate;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 설정된 최소 지원 버전을 {@link AppVersion}으로 변환해 {@link AppUpdatePolicy}를 등록합니다. 형식이 올바르지 않은 최소 지원 버전이 있으면
 * 애플리케이션이 시작되지 않습니다.
 */
@Configuration
@EnableConfigurationProperties(AppUpdateProperties.class)
class AppUpdateConfig {

  @Bean
  AppUpdatePolicy appUpdatePolicy(AppUpdateProperties properties) {
    Map<AppPlatform, AppVersion> minimumVersions = new EnumMap<>(AppPlatform.class);
    if (properties.minimumVersions() != null) {
      properties
          .minimumVersions()
          .forEach(
              (platform, value) ->
                  minimumVersions.put(
                      platform,
                      AppVersion.from(value)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Invalid minimum app version for "
                                          + platform.value()
                                          + ": "
                                          + value))));
    }
    return new AppUpdatePolicy(minimumVersions);
  }
}
