package works.momens.server.mcp.transport.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.mcp.transport.McpToolExecutor;

@Slf4j
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class McpTransportMethodHandler {

  private static final String SERVER_NAME = "momens-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private final ObjectMapper objectMapper;
  private final McpToolCatalog toolCatalog;
  private final McpToolExecutor toolExecutor;
  private final McpTransportResponseFactory responseFactory;

  ResponseEntity<JsonNode> handle(
      String method, JsonNode id, JsonNode params, McpAuthenticationContext authenticationContext) {
    return switch (method) {
      case "server/discover" -> responseFactory.jsonRpcResult(id, discoverResult());
      case "tools/list" ->
          responseFactory.jsonRpcResult(id, modernToolsListResult(authenticationContext));
      case "tools/call" -> callTool(id, params, authenticationContext);
      default -> responseFactory.notFound(id, -32601, "Method not found");
    };
  }

  ResponseEntity<JsonNode> handleInitializationProtocol(
      String method, JsonNode id, JsonNode params, McpAuthenticationContext authenticationContext) {
    return switch (method) {
      case "initialize" -> initialize(id, params);
      case "ping" -> responseFactory.jsonRpcResult(id, objectMapper.createObjectNode());
      case "tools/list" ->
          responseFactory.jsonRpcResult(id, toolsListResult(authenticationContext));
      case "tools/call" -> callTool(id, params, authenticationContext);
      default -> responseFactory.jsonRpcError(id, -32601, "Method not found");
    };
  }

  private ResponseEntity<JsonNode> initialize(JsonNode id, JsonNode params) {
    JsonNode clientInfo = params.path("clientInfo");
    if (!params.path("protocolVersion").isTextual()
        || params.path("protocolVersion").asText().isBlank()
        || !params.path("capabilities").isObject()
        || !clientInfo.path("name").isTextual()
        || !clientInfo.path("version").isTextual()) {
      return responseFactory.jsonRpcError(id, -32602, "Invalid params");
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.put("protocolVersion", McpProtocol.INITIALIZATION_VERSION);
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    setServerInfo(result.putObject("serverInfo"));
    return responseFactory.jsonRpcResult(id, result);
  }

  private ResponseEntity<JsonNode> callTool(
      JsonNode id, JsonNode params, McpAuthenticationContext context) {
    if (!params.path("name").isTextual()
        || (params.has("arguments") && !params.get("arguments").isObject())) {
      return responseFactory.jsonRpcError(id, -32602, "Invalid params");
    }
    JsonNode arguments =
        params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
    String name = params.get("name").asText();
    String registeredToolName = "unknown";
    try {
      registeredToolName =
          toolCatalog.list(context).stream()
              .map(McpToolDefinition::name)
              .filter(name::equals)
              .findFirst()
              .orElse("unknown");
      return toolExecutor
          .call(name, arguments, context)
          .map(result -> responseFactory.jsonRpcResult(id, result))
          .orElseGet(() -> responseFactory.jsonRpcError(id, -32602, "Unknown tool"));
    } catch (RuntimeException exception) {
      log.error(
          "MCP tool execution failed tool={} grantId={} workspaceId={} exceptionType={}",
          registeredToolName,
          context.grantId(),
          context.workspaceId(),
          exception.getClass().getSimpleName());
      return responseFactory.jsonRpcError(id, -32603, "Internal error");
    }
  }

  private ObjectNode discoverResult() {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("supportedVersions").add(McpProtocol.VERSION);
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    addServerInfo(result);
    result.put("ttlMs", 3600000);
    result.put("cacheScope", "public");
    return result;
  }

  private ObjectNode toolsListResult(McpAuthenticationContext authenticationContext) {
    ObjectNode result = objectMapper.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    for (McpToolDefinition definition : toolCatalog.list(authenticationContext)) {
      ObjectNode tool = tools.addObject();
      tool.put("name", definition.name());
      if (definition.description() != null) {
        tool.put("description", definition.description());
      }
      tool.set("inputSchema", definition.inputSchema());
    }
    return result;
  }

  private ObjectNode modernToolsListResult(McpAuthenticationContext authenticationContext) {
    ObjectNode result = toolsListResult(authenticationContext);
    result.put("resultType", "complete");
    result.put("ttlMs", 300000);
    result.put("cacheScope", "private");
    addServerInfo(result);
    return result;
  }

  private static void addServerInfo(ObjectNode result) {
    setServerInfo(result.putObject("_meta").putObject("io.modelcontextprotocol/serverInfo"));
  }

  private static void setServerInfo(ObjectNode serverInfo) {
    serverInfo.put("name", SERVER_NAME).put("version", SERVER_VERSION);
  }
}
