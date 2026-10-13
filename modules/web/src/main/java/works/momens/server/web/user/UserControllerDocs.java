package works.momens.server.web.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.user.UserErrorCode;
import works.momens.server.web.user.dto.request.UpdateMeRequest;
import works.momens.server.web.user.dto.response.MeResponse;

@Tag(name = "User", description = "사용자 프로필 API")
interface UserControllerDocs {

  @Operation(operationId = "getMe", summary = "내 프로필 조회", description = "인증된 사용자 본인의 프로필을 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "내 프로필 조회 성공",
      content = @Content(schema = @Schema(implementation = MeResponse.class)))
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  MeResponse getMe(Principal principal);

  @Operation(
      operationId = "updateMe",
      summary = "내 프로필 수정",
      description = "인증된 사용자 본인의 프로필을 부분 수정합니다. 제공된 필드만 갱신합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "내 프로필 수정 성공",
      content = @Content(schema = @Schema(implementation = MeResponse.class)))
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  MeResponse updateMe(Principal principal, UpdateMeRequest request);
}
