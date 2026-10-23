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
      description = "마이페이지에 표시할 현재 사용자의 이름, 이메일, 프로필 사진을 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "계정 정보 조회 성공. 프로필 사진이 없는 경우 `avatar_url`은 `null`로 반환됩니다.",
      content = @Content(schema = @Schema(implementation = MeResponse.class)))
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  MeResponse getMe(Principal principal);
}
