package works.momens.server.mcp.oauth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;

@Tag(name = "McpInteraction", description = "MCP 워크스페이스 접근 동의 API")
interface McpInteractionControllerDocs {
  @ApiException(
      value = CommonErrorCode.class,
      codes = {"AUTH_UNAUTHORIZED", "AUTH_INVALID_TOKEN"})
  @Operation(operationId = "getMcpInteraction", summary = "MCP 동의 요청 조회")
  @ApiResponse(
      responseCode = "200",
      description = "동의 요청과 사용자의 워크스페이스 목록",
      content =
          @Content(
              schema =
                  @Schema(implementation = McpInteractionController.InteractionResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 interaction",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"invalid_interaction\"}")))
  @ApiResponse(
      responseCode = "409",
      description = "이미 결정된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_decided\"}")))
  @ApiResponse(
      responseCode = "410",
      description = "만료된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_expired\"}")))
  McpInteractionController.InteractionResponse get(
      @Parameter(hidden = true) Principal principal, UUID id);

  @ApiException(
      value = CommonErrorCode.class,
      codes = {"AUTH_UNAUTHORIZED", "AUTH_INVALID_TOKEN"})
  @Operation(operationId = "approveMcpInteraction", summary = "MCP 워크스페이스 접근 승인")
  @ApiResponse(
      responseCode = "200",
      description = "authorization code를 포함한 client 복귀 URL",
      content =
          @Content(
              schema = @Schema(implementation = McpInteractionController.RedirectResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 interaction 또는 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"invalid_interaction\"}")))
  @ApiResponse(
      responseCode = "403",
      description = "워크스페이스 멤버가 아님",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"forbidden\"}")))
  @ApiResponse(
      responseCode = "409",
      description = "이미 결정된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_decided\"}")))
  @ApiResponse(
      responseCode = "410",
      description = "만료된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_expired\"}")))
  McpInteractionController.RedirectResponse approve(
      @Parameter(hidden = true) Principal principal,
      UUID id,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      schema =
                          @Schema(implementation = McpInteractionController.ApproveRequest.class),
                      examples =
                          @ExampleObject(
                              value =
                                  "{\"workspace_id\":\"00000000-0000-4000-8000-000000000001\"}")))
          McpInteractionController.ApproveRequest request);

  @ApiException(
      value = CommonErrorCode.class,
      codes = {"AUTH_UNAUTHORIZED", "AUTH_INVALID_TOKEN"})
  @Operation(operationId = "denyMcpInteraction", summary = "MCP 접근 요청 거절")
  @ApiResponse(
      responseCode = "200",
      description = "access_denied를 포함한 client 복귀 URL",
      content =
          @Content(
              schema = @Schema(implementation = McpInteractionController.RedirectResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 interaction",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"invalid_interaction\"}")))
  @ApiResponse(
      responseCode = "409",
      description = "이미 결정된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_decided\"}")))
  @ApiResponse(
      responseCode = "410",
      description = "만료된 요청",
      content = @Content(examples = @ExampleObject(value = "{\"error\":\"interaction_expired\"}")))
  McpInteractionController.RedirectResponse deny(
      @Parameter(hidden = true) Principal principal, UUID id);
}
