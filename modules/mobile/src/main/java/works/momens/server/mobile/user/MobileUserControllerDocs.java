package works.momens.server.mobile.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.user.dto.response.MeResponse;
import works.momens.server.user.UserErrorCode;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface MobileUserControllerDocs {

  @Operation(
      operationId = "mobileGetMe",
      summary = "모바일 계정 정보 조회",
      description = "마이페이지에 표시할 현재 사용자의 계정 정보를 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "계정 정보 조회 성공",
      content = @Content(schema = @Schema(implementation = MeResponse.class)))
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  MeResponse getMe(Principal principal);

  @Operation(
      operationId = "mobileCompleteOnboarding",
      summary = "모바일 온보딩 완료 기록",
      description = "현재 로그인한 사용자의 모바일 앱 온보딩(코치마크) 완료를 기록합니다. 동일한 요청을 여러 번 보내더라도 최초 완료 시각을 유지합니다.")
  @ApiResponse(
      responseCode = "204",
      description = "온보딩 완료를 기록했습니다. 이미 온보딩을 완료한 사용자가 다시 요청하더라도 최초 완료 시각은 변경하지 않습니다.")
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  void completeOnboarding(Principal principal);
}
