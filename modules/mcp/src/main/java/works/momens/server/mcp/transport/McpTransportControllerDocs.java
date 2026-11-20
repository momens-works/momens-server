package works.momens.server.mcp.transport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "McpTransport", description = "MCP Streamable HTTP transport API")
interface McpTransportControllerDocs {

  @Operation(operationId = "mcpPost", summary = "MCP Streamable HTTP transport")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "JSON-RPC response",
        content = @Content(schema = @Schema(type = "object"))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request or header mismatch",
        content = @Content(schema = @Schema(type = "object"))),
    @ApiResponse(
        responseCode = "401",
        description = "Bearer authentication required",
        content = @Content(schema = @Schema(type = "object"))),
    @ApiResponse(
        responseCode = "403",
        description = "Origin is not allowed",
        content = @Content(schema = @Schema(type = "object"))),
    @ApiResponse(
        responseCode = "404",
        description = "MCP method not found",
        content = @Content(schema = @Schema(type = "object")))
  })
  @Parameters({
    @Parameter(
        name = "MCP-Protocol-Version",
        in = ParameterIn.HEADER,
        required = true,
        schema = @Schema(type = "string", example = "2026-07-28")),
    @Parameter(
        name = "Mcp-Method",
        in = ParameterIn.HEADER,
        required = true,
        schema = @Schema(type = "string", example = "tools/list")),
    @Parameter(
        name = "Accept",
        in = ParameterIn.HEADER,
        required = true,
        description = "Clients should advertise both JSON and SSE response support.",
        schema = @Schema(type = "string", example = "application/json, text/event-stream")),
    @Parameter(
        name = "Mcp-Name",
        in = ParameterIn.HEADER,
        required = false,
        description =
            "Required for tools/call, resources/read, and prompts/get; supports the Base64 sentinel format.",
        schema = @Schema(type = "string"))
  })
  @RequestBody(required = true, content = @Content(schema = @Schema(type = "object")))
  ResponseEntity<JsonNode> post(String request, HttpServletRequest httpServletRequest);
}
