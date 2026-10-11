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
import works.momens.server.web.source.dto.request.ConfigureFigmaRequest;
import works.momens.server.web.source.dto.response.SourceConnectionResponse;
import works.momens.server.workspace.core.WorkspaceErrorCode;

@Tag(name = "Web", description = "웹 진입 API")
interface SourceConnectionWriteControllerDocs {

  @Operation(
      operationId = "configureFigmaSourceConnection",
      summary = "Figma 연결 설정·활성화",
      description =
          "admin 또는 owner가 팀 webhook과 파일 허용 목록을 설정합니다. 모든 연결 상태에서 재설정할 수 있습니다."
              + " 성공 시 ACTIVE로 전환하고 disabled_at을 비웁니다. 등록 실패 시 기존 설정을 유지합니다."
              + " 동시 변경은 409, 만료·누락된 토큰은 재인증 필요 오류입니다. 오류는 Standard 형식입니다.")
  @ApiResponse(
      responseCode = "200",
      description = "활성화된 연결",
      content =
          @Content(
              schema = @Schema(implementation = SourceConnectionResponse.class),
              examples =
                  @ExampleObject(
                      value =
                          """
              {
                "id": "00000000-0000-4000-8000-000000000001",
                "workspace_id": "00000000-0000-4000-8000-000000000002",
                "source_type": "FIGMA", "status": "ACTIVE",
                "captures_read_count": 0, "candidates_extracted_count": 0,
                "metadata": {"team_id": "123456789", "file_keys": ["file-key"], "webhook_id": "webhook-id"},
                "created_at": "2026-10-07T00:00:00Z", "updated_at": "2026-10-07T00:00:00Z"
              }
              """)))
  @ApiException(
      value = SourceErrorCode.class,
      codes = {
        "SOURCE_CONNECTION_NOT_FOUND", "SOURCE_NOT_FIGMA_CONNECTION", "SOURCE_FIGMA_INVALID_CONFIG",
        "SOURCE_FIGMA_WEBHOOK_UNCONFIGURED", "SOURCE_FIGMA_REAUTH_REQUIRED",
            "SOURCE_FIGMA_CREDENTIAL_INVALID",
        "SOURCE_FIGMA_WEBHOOK_FAILED", "SOURCE_CONNECTION_CHANGED"
      })
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  SourceConnectionResponse configureFigma(
      UUID connectionId, ConfigureFigmaRequest request, Principal principal);

  @Operation(
      operationId = "disableSourceConnection",
      summary = "source 연결 비활성화",
      description =
          "admin 또는 owner가 source 연결을 비활성화합니다. 이미 비활성화된 연결은 400을 반환합니다."
              + " Figma webhook 삭제 실패는 비활성화를 막지 않습니다. 오류는 Standard 형식입니다.")
  @ApiResponse(
      responseCode = "200",
      description = "연결 비활성화 성공",
      content =
          @Content(
              schema = @Schema(implementation = WebMessageResponse.class),
              examples = @ExampleObject(value = "{\"message\":\"disabled\"}")))
  @ApiException(
      value = SourceErrorCode.class,
      codes = {"SOURCE_CONNECTION_NOT_FOUND", "SOURCE_CONNECTION_ALREADY_DISABLED"})
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse disable(
      @Parameter(description = "source 연결 식별자") UUID connectionId, Principal principal);
}
