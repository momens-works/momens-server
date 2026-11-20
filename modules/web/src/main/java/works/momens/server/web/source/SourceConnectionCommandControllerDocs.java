package works.momens.server.web.source;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.web.dto.response.WebMessageResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface SourceConnectionCommandControllerDocs {

  @Operation(
      operationId = "requestSourceConnectionResync",
      summary = "source 연결 재동기화 요청",
      description =
          "연결의 admin 또는 owner가 재동기화를 요청합니다. 연결 상태와 관계없이 요청 시각을 갱신하며,"
              + " 연결을 활성화하거나 수집 완료를 기다리지 않습니다. 반복 요청은 요청 시각을 다시 갱신합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "재동기화 요청 기록 성공",
      content =
          @Content(
              schema = @Schema(implementation = WebMessageResponse.class),
              examples = @ExampleObject(value = "{\"message\":\"resync requested\"}")))
  @ApiException(
      value = SourceErrorCode.class,
      codes = {"SOURCE_CONNECTION_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse requestResync(
      @Parameter(description = "source 연결 식별자") UUID connectionId, Principal principal);
}
