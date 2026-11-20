package works.momens.server.web.source;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.web.source.dto.response.WebSourceRefResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface SourceRefControllerDocs {

  @Operation(
      operationId = "verifySourceRef",
      summary = "source-ref 검증 완료 표시",
      description = "source-ref를 확인한 것으로 표시하고 검증한 사용자와 시각을 기록합니다. 해당 워크스페이스의 멤버 권한이 필요합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "source-ref 검증 완료 표시 성공",
      content = @Content(schema = @Schema(implementation = WebSourceRefResponse.class)))
  @ApiException(
      value = SourceErrorCode.class,
      codes = {"SOURCE_REF_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebSourceRefResponse verify(
      @Parameter(description = "source-ref 식별자") UUID sourceRefId, Principal principal);
}
