package works.momens.server.mobile.appupdate;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플랫폼별 최소 지원 버전 설정({@code momens.mobile.app-update.minimum-versions})을 바인딩합니다. 버전 형식은 {@link
 * AppUpdateConfig}에서 검증하고, 모든 플랫폼의 최소 지원 버전이 설정되어 있는지는 {@link AppUpdatePolicy}에서 검증합니다.
 */
@ConfigurationProperties("momens.mobile.app-update")
record AppUpdateProperties(Map<AppPlatform, String> minimumVersions) {}
