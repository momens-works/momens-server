package works.momens.server.source.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import works.momens.server.common.api.ApiException;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.presentation.dto.response.SourceOAuthCallbackResponse;

/** provider가 호출하는 공개 경로이므로 로그인 정보 없이 호출되며, 승인 흐름을 시작한 사용자와 워크스페이스, provider는 서명된 state만으로 판정합니다. */
@SecurityRequirements
@Tag(name = "Source", description = "source 연동 API")
interface SourceOAuthCallbackControllerDocs {

  @Operation(
      operationId = "completeSourceConnection",
      summary = "provider 승인 결과 수신",
      description = "provider가 승인 결과와 함께 호출하는 경로입니다. state를 검증하고 토큰을 교환한 뒤 연결 정보를 저장합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "302", description = "승인 성공 후 설정된 URL로 이동", content = @Content),
    @ApiResponse(
        responseCode = "200",
        description = "이동할 URL이 설정되지 않은 경우 연결 정보 반환",
        content = @Content(schema = @Schema(implementation = SourceOAuthCallbackResponse.class)))
  })
  @ApiException(
      value = SourceErrorCode.class,
      codes = {
        "SOURCE_OAUTH_INVALID_REQUEST",
        "SOURCE_OAUTH_INVALID_STATE",
        "SOURCE_UNSUPPORTED_PROVIDER",
        "SOURCE_PROVIDER_UNCONFIGURED",
        "SOURCE_OAUTH_EXCHANGE_FAILED"
      })
  ResponseEntity<SourceOAuthCallbackResponse> callback(
      @Parameter(description = "provider가 발급한 승인 코드") String code,
      @Parameter(description = "연결 시작 시 서버가 서명해 provider에 전달한 값") String state);
}
