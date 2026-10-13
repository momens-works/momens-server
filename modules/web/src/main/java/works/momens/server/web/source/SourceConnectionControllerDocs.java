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
import works.momens.server.web.source.dto.response.SourceConnectionsResponse;
import works.momens.server.web.source.dto.response.SourceInstallResponse;
import works.momens.server.workspace.core.WorkspaceErrorCode;

@Tag(name = "Web", description = "웹 진입 API")
interface SourceConnectionControllerDocs {

  @Operation(
      operationId = "listSourceConnections",
      summary = "source 연결 목록 조회",
      description = "워크스페이스에 연결된 외부 source를 생성 시각 내림차순으로 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "source 연결 목록 조회 성공",
      content = @Content(schema = @Schema(implementation = SourceConnectionsResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  SourceConnectionsResponse list(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "startSourceConnection",
      summary = "source 연결 시작",
      description =
          "provider 승인 화면으로 이동할 URL을 발급합니다. admin 또는 owner 권한이 필요합니다. 지원하지 않는 provider는 400,"
              + " 서버에 해당 provider 설정이 없으면 500을 반환합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "provider 승인 URL 발급 성공",
      content = @Content(schema = @Schema(implementation = SourceInstallResponse.class)))
  @ApiException(
      value = SourceErrorCode.class,
      codes = {"SOURCE_UNSUPPORTED_PROVIDER", "SOURCE_PROVIDER_UNCONFIGURED"})
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  SourceInstallResponse install(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(
              description =
                  "연결할 provider 이름입니다. github, slack, notion, figma 중 하나이며 대소문자를 구분하지 않습니다.")
          String provider,
      Principal principal);
}
