package works.momens.server.mobile.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
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

  @Operation(
      operationId = "mobileCompleteOnboarding",
      summary = "모바일 온보딩 완료 기록",
      description =
          "현재 로그인한 사용자의 모바일 앱 온보딩(코치마크) 완료를 기록합니다. 요청 본문은 없으며, 동일한 요청을 여러 번 보내더라도 최초 완료 시각을 유지합니다.")
  @ApiResponse(responseCode = "201", description = "온보딩 완료를 최초로 기록했습니다.")
  @ApiResponse(
      responseCode = "204",
      description = "이미 온보딩을 완료한 사용자의 요청을 멱등하게 처리했습니다. 최초 완료 시각은 변경하지 않습니다.")
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  ResponseEntity<Void> completeOnboarding(Principal principal);
}
