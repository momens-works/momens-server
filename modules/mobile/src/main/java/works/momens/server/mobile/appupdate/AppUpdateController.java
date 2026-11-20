package works.momens.server.mobile.appupdate;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.mobile.appupdate.dto.response.AppUpdateResponse;

/**
 * 모바일 앱의 업데이트 필요 여부를 확인하는 엔드포인트입니다.
 *
 * <p>업데이트하지 않은 앱도 로그인 전에 호출해야 하므로 {@code SecurityConfig.PUBLIC_PATHS}에 등록한 공개 경로입니다. 이미 배포된 앱이 계속
 * 호출하는 엔드포인트이므로 경로, 쿼리 파라미터, 응답 형식은 변경하지 않습니다. 이후 {@code API-Version}이 올라가더라도 {@code version = "1"}
 * mapping은 유지합니다.
 */
@RestController
@RequiredArgsConstructor
class AppUpdateController implements AppUpdateControllerDocs {

  private static final String APP_VERSION = "app_version";

  private final AppUpdatePolicy appUpdatePolicy;

  @Override
  @GetMapping(path = "/api/mobile/app-update", version = "1")
  public AppUpdateResponse checkAppUpdate(
      @RequestParam(name = "platform") AppPlatform platform,
      @RequestParam(name = APP_VERSION) String appVersion) {
    AppVersion version =
        AppVersion.from(appVersion)
            .orElseThrow(() -> FieldValidationException.forField(APP_VERSION));
    return new AppUpdateResponse(appUpdatePolicy.isUpdateRequired(platform, version));
  }
}
