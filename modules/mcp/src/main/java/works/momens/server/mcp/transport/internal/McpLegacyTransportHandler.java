package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;

/** 2025-06-18·2025-11-25 클라이언트의 요청을 처리합니다. 세션을 발급하지 않습니다. */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class McpLegacyTransportHandler {

  private final ObjectMapper objectMapper;
  private final McpTransportRequestValidator requestValidator;
  private final McpTransportMethodHandler methodHandler;
  private final McpTransportResponseFactory responseFactory;

  ResponseEntity<JsonNode> handle(
      JsonNode request, HttpServletRequest httpRequest, McpAuthenticationContext context) {
    boolean notification = requestValidator.isJsonRpcNotification(request);
    if (!notification && !requestValidator.isJsonRpcRequest(request)) {
      return responseFactory.badRequest(null, -32600, "Invalid Request");
    }
    JsonNode id = request.get("id");
    String headerVersion = httpRequest.getHeader(McpProtocol.VERSION_HEADER);
    if (headerVersion != null && !McpProtocol.LEGACY_VERSIONS.contains(headerVersion)) {
      return responseFactory.unsupportedProtocolVersion(
          id, headerVersion, McpProtocol.LEGACY_VERSIONS);
    }
    if (notification) {
      return ResponseEntity.accepted().build();
    }
    JsonNode params = request.path("params");
    return switch (request.get("method").asText()) {
      case "initialize" -> initialize(id, params);
      case "ping" -> responseFactory.jsonRpcResult(id, objectMapper.createObjectNode());
      case "tools/list" -> responseFactory.jsonRpcResult(id, toolsListResult(context));
      case "tools/call" -> callTool(id, params, context);
      default -> responseFactory.jsonRpcError(id, -32601, "Method not found");
    };
  }

  private ResponseEntity<JsonNode> initialize(JsonNode id, JsonNode params) {
    if (!params.path("protocolVersion").isTextual()) {
      return responseFactory.jsonRpcError(id, -32602, "Invalid params");
    }
    String requested = params.get("protocolVersion").asText();
    ObjectNode result = objectMapper.createObjectNode();
    result.put(
        "protocolVersion",
        McpProtocol.LEGACY_VERSIONS.contains(requested)
            ? requested
            : McpProtocol.LEGACY_VERSIONS.getFirst());
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    result
        .putObject("serverInfo")
        .put("name", McpProtocol.SERVER_NAME)
        .put("version", McpProtocol.SERVER_VERSION);
    return responseFactory.jsonRpcResult(id, result);
  }

  private ObjectNode toolsListResult(McpAuthenticationContext context) {
    ObjectNode result = objectMapper.createObjectNode();
    result.set("tools", methodHandler.tools(context));
    return result;
  }

  private ResponseEntity<JsonNode> callTool(
      JsonNode id, JsonNode params, McpAuthenticationContext context) {
    ResponseEntity<JsonNode> response = methodHandler.callTool(id, params, context);
    // resultType은 2026-07-28 결과에만 있는 필드입니다.
    if (response.getBody().path("result") instanceof ObjectNode result) {
      result.remove("resultType");
    }
    return response;
  }
}
