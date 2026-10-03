package works.momens.server.mobile.appupdate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.appupdate.dto.response.AppUpdateResponse;

/**
 * {@code /api/mobile/app-update}의 OpenAPI 문서입니다. Swagger 애너테이션을 컨트롤러 구현과
 * 분리합니다(docs/spec/openapi.md).
 *
 * <p>로그인 전에 호출하는 공개 엔드포인트이므로 {@code OpenApiConfig}에서 설정한 전역 Bearer 인증 요구 사항을
 * {@code @SecurityRequirements}로 제외합니다.
 */
@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface AppUpdateControllerDocs {

  @Operation(
      operationId = "mobileCheckAppUpdate",
      summary = "모바일 앱 업데이트 필요 여부 확인",
      description =
          "앱의 플랫폼과 버전을 받아 해당 플랫폼의 최소 지원 버전보다 낮은지 판정합니다. 인증 없이 호출할 수 있습니다. 업데이트하지 않은 앱도 계속 호출하는 엔드포인트이므로 경로와 요청, 응답 형식은 변경하지 않습니다. 이후 필요한 정보는 optional 필드를 추가하는 방식으로 확장합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "판정 성공. 앱 버전이 해당 플랫폼의 최소 지원 버전보다 낮으면 update_required는 true입니다.",
      content = @Content(schema = @Schema(implementation = AppUpdateResponse.class)))
  @SecurityRequirements
  @ApiException(CommonErrorCode.class)
  AppUpdateResponse checkAppUpdate(
      @Parameter(description = "앱이 실행되는 플랫폼입니다.") AppPlatform platform,
      @Parameter(description = "앱 버전입니다. 점으로 구분된 숫자를 1개부터 3개까지 허용합니다.", example = "1.0.0")
          String appVersion);
}
