package works.momens.server.web.workspace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.web.workspace.dto.response.McpGrantErrorResponse;
import works.momens.server.web.workspace.dto.response.McpGrantsResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface McpGrantControllerDocs {
  @Operation(
      operationId = "listWorkspaceMcpGrants",
      summary = "내 MCP 연결 목록 조회",
      description = "워크스페이스 멤버가 자신이 승인한 활성 연결만 생성 시각 내림차순으로 조회합니다. 빈 목록은 grants: []입니다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content =
          @Content(
              schema = @Schema(implementation = McpGrantsResponse.class),
              examples = @ExampleObject(name = "empty", value = "{\"grants\":[]}")))
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 워크스페이스 식별자",
      content =
          @Content(
              schema = @Schema(implementation = McpGrantErrorResponse.class),
              examples = @ExampleObject(value = "{\"error\":\"invalid workspace id\"}")))
  @ApiResponse(
      responseCode = "403",
      description = "워크스페이스 멤버가 아님",
      content =
          @Content(
              schema = @Schema(implementation = McpGrantErrorResponse.class),
              examples = @ExampleObject(value = "{\"error\":\"forbidden\"}")))
  @ApiException(
      value = CommonErrorCode.class,
      codes = {"AUTH_UNAUTHORIZED", "AUTH_INVALID_TOKEN", "COMMON_INTERNAL_SERVER_ERROR"})
  McpGrantsResponse list(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "revokeWorkspaceMcpGrant",
      summary = "내 MCP 연결 폐기",
      description =
          "현재 사용자의 활성 연결과 연결된 access/refresh token을 함께 폐기합니다. 비멤버, 다른 사용자·워크스페이스의 연결, 없거나 이미 폐기된 연결은 동일하게 403입니다.")
  @ApiResponse(responseCode = "204", description = "폐기 성공", content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 식별자",
      content =
          @Content(
              schema = @Schema(implementation = McpGrantErrorResponse.class),
              examples = {
                @ExampleObject(name = "workspace", value = "{\"error\":\"invalid workspace id\"}"),
                @ExampleObject(name = "grant", value = "{\"error\":\"invalid grant id\"}")
              }))
  @ApiResponse(
      responseCode = "403",
      description = "연결 접근 불가",
      content =
          @Content(
              schema = @Schema(implementation = McpGrantErrorResponse.class),
              examples = @ExampleObject(value = "{\"error\":\"forbidden\"}")))
  @ApiException(
      value = CommonErrorCode.class,
      codes = {"AUTH_UNAUTHORIZED", "AUTH_INVALID_TOKEN", "COMMON_INTERNAL_SERVER_ERROR"})
  ResponseEntity<Void> revoke(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(description = "연결 식별자") UUID grantId,
      Principal principal);
}
