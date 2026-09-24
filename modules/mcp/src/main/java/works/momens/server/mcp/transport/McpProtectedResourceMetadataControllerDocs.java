package works.momens.server.mcp.transport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "McpOAuth", description = "MCP OAuth metadata API")
interface McpProtectedResourceMetadataControllerDocs {

  @Operation(
      operationId = "mcpProtectedResourceMetadata",
      summary = "MCP OAuth protected resource metadata")
  @ApiResponse(
      responseCode = "200",
      description = "Protected resource metadata",
      content = @Content(schema = @Schema(type = "object")))
  @SecurityRequirements
  ResponseEntity<JsonNode> get();
}
