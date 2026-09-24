package works.momens.server.mcp.transport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "McpOAuth", description = "MCP OAuth metadata API")
interface McpAuthorizationServerMetadataControllerDocs {

  @Operation(
      operationId = "getMcpAuthorizationServerMetadata",
      summary = "MCP OAuth authorization server metadata")
  @ApiResponse(
      responseCode = "200",
      description = "Authorization server metadata",
      content = @Content(schema = @Schema(type = "object")))
  ResponseEntity<JsonNode> get();
}
