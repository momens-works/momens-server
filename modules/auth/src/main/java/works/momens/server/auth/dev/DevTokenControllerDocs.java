package works.momens.server.auth.dev;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import works.momens.server.auth.dev.dto.request.DevTokenRequest;
import works.momens.server.auth.dev.dto.response.DevTokenResponse;
import works.momens.server.auth.internal.application.DevTokenErrorCode;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.user.UserErrorCode;

/**
 * dev 전용 토큰 발급 API 문서. dev 계열 프로필에서만 노출됩니다({@code @DevOnly}). 공개 경로이므로 OpenApiConfig의 전역 Bearer
 * 요구에서 {@code @SecurityRequirements}(빈 값)로 제외하고, 호출자 제한은 {@code X-Dev-Token-Secret} 헤더로 합니다.
 */
@Tag(name = "Auth (dev)", description = "dev 전용 인증 도구")
interface DevTokenControllerDocs {

  @Operation(
      operationId = "devIssueToken",
      summary = "dev 테스트 사용자 토큰 발급",
      description = "허용된 테스트 사용자의 access token을 발급합니다. dev 계열 프로필에서만 동작하며, 호출자는 공유 시크릿 헤더로 제한합니다.")
  @Parameter(
      name = "X-Dev-Token-Secret",
      in = ParameterIn.HEADER,
      required = true,
      description = "호출자 제한용 공유 시크릿",
      schema = @Schema(type = "string"))
  @ApiResponse(
      responseCode = "200",
      description = "토큰 발급 성공",
      content = @Content(schema = @Schema(implementation = DevTokenResponse.class)))
  @SecurityRequirements
  @ApiException(
      value = DevTokenErrorCode.class,
      codes = {"AUTH_DEV_TOKEN_SECRET_INVALID", "AUTH_DEV_TOKEN_EMAIL_NOT_ALLOWED"})
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_EMAIL_AMBIGUOUS"})
  @ApiException(CommonErrorCode.class)
  DevTokenResponse issueDevToken(String secret, DevTokenRequest request);
}
