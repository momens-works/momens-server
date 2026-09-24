package works.momens.server.mcp.transport;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
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
public class McpTransportController {

  private final McpTransportHandler transportHandler;

  McpTransportController(McpTransportHandler transportHandler) {
    this.transportHandler = transportHandler;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "mcpPost", summary = "MCP Streamable HTTP transport")
  ResponseEntity<JsonNode> post(
      @RequestBody JsonNode request, HttpServletRequest httpServletRequest) {
    return transportHandler.handle(request, httpServletRequest);
  }
}
