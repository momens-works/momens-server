package works.momens.server.mcp.transport.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;

@Component
class McpTransportMethodHandler {

  private static final String PROTOCOL_VERSION = "2026-07-28";
  private static final String SERVER_NAME = "momens-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private final ObjectMapper objectMapper;
  private final McpToolCatalog toolCatalog;
  private final McpTransportResponseFactory responseFactory;

  McpTransportMethodHandler(
      ObjectMapper objectMapper,
      McpToolCatalog toolCatalog,
      McpTransportResponseFactory responseFactory) {
    this.objectMapper = objectMapper;
    this.toolCatalog = toolCatalog;
    this.responseFactory = responseFactory;
  }

  ResponseEntity<JsonNode> handle(
      String method, JsonNode id, McpAuthenticationContext authenticationContext) {
    return switch (method) {
      case "server/discover" -> responseFactory.jsonRpcResult(id, discoverResult());
      case "tools/list" ->
          responseFactory.jsonRpcResult(id, toolsListResult(authenticationContext));
      default -> responseFactory.notFound(id, -32601, "Method not found");
    };
  }

  private ObjectNode discoverResult() {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("supportedVersions").add(PROTOCOL_VERSION);
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    result
        .putObject("_meta")
        .putObject("io.modelcontextprotocol/serverInfo")
        .put("name", SERVER_NAME)
        .put("version", SERVER_VERSION);
    result.put("ttlMs", 3600000);
    result.put("cacheScope", "public");
    return result;
  }

  private ObjectNode toolsListResult(McpAuthenticationContext authenticationContext) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("resultType", "complete");
    var tools = result.putArray("tools");
    for (McpToolDefinition definition : toolCatalog.list(authenticationContext)) {
      ObjectNode tool = tools.addObject();
      tool.put("name", definition.name());
      if (definition.description() != null) {
        tool.put("description", definition.description());
      }
      tool.set("inputSchema", definition.inputSchema());
    }
    result.put("ttlMs", 300000);
    result.put("cacheScope", "private");
    return result;
  }
}
