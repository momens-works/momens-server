package works.momens.server.mcp.transport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.internal.McpTransportHandler;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpTransportController {

  private final McpTransportHandler transportHandler;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "mcpPost", summary = "MCP Streamable HTTP transport")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "JSON-RPC response"),
    @ApiResponse(responseCode = "400", description = "Invalid request or header mismatch"),
    @ApiResponse(responseCode = "401", description = "Bearer authentication required"),
    @ApiResponse(responseCode = "404", description = "MCP method not found")
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
  ResponseEntity<JsonNode> post(
      @RequestBody JsonNode request, HttpServletRequest httpServletRequest) {
    return transportHandler.handle(request, httpServletRequest);
  }
}
