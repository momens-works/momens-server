package works.momens.server.mcp.transport.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;

@RestController
@RequestMapping("/api/mcp")
class McpTransportController {

  private static final String PROTOCOL_VERSION = "2026-07-28";
  private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
  private static final String METHOD_HEADER = "Mcp-Method";
  private static final String NAME_HEADER = "Mcp-Name";
  private static final String SERVER_NAME = "momens-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private final ObjectMapper objectMapper;
  private final McpBearerTokenVerifier bearerTokenVerifier;
  private final McpToolCatalog toolCatalog;

  McpTransportController(
      ObjectMapper objectMapper,
      McpBearerTokenVerifier bearerTokenVerifier,
      McpToolCatalog toolCatalog) {
    this.objectMapper = objectMapper;
    this.bearerTokenVerifier = bearerTokenVerifier;
    this.toolCatalog = toolCatalog;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "mcpPost", summary = "MCP Streamable HTTP transport")
  ResponseEntity<JsonNode> post(
      @RequestBody JsonNode request, HttpServletRequest httpServletRequest) {
    Optional<McpAuthenticationContext> authentication = authenticate(httpServletRequest);
    if (authentication.isEmpty()) {
      return unauthorized(httpServletRequest);
    }
    if (!isRequest(request)) {
      return jsonRpcError(null, -32600, "Invalid Request");
    }

    JsonNode id = request.get("id");
    String method = request.get("method").asText();
    if (!hasLatestProtocolHeaders(httpServletRequest, request, method)) {
      return badRequest(id, -32600, "Invalid Request");
    }
    return switch (method) {
      case "server/discover" -> jsonRpcResult(id, discoverResult());
      case "tools/list" -> jsonRpcResult(id, toolsListResult(authentication.get()));
      default -> jsonRpcError(id, -32601, "Method not found");
    };
  }

  private boolean hasLatestProtocolHeaders(
      HttpServletRequest request, JsonNode body, String method) {
    if (!PROTOCOL_VERSION.equals(request.getHeader(PROTOCOL_VERSION_HEADER))
        || !method.equals(request.getHeader(METHOD_HEADER))) {
      return false;
    }
    JsonNode metadata = body.path("params").path("_meta");
    if (!PROTOCOL_VERSION.equals(metadata.path("io.modelcontextprotocol/protocolVersion").asText())
        || !metadata.path("io.modelcontextprotocol/clientInfo").isObject()
        || !metadata.path("io.modelcontextprotocol/clientInfo").path("name").isTextual()
        || !metadata.path("io.modelcontextprotocol/clientInfo").path("version").isTextual()
        || !metadata.path("io.modelcontextprotocol/clientCapabilities").isObject()) {
      return false;
    }
    String name = request.getHeader(NAME_HEADER);
    if (name == null || name.isBlank()) {
      return false;
    }
    if ("tools/call".equals(method)) {
      return name.equals(body.path("params").path("name").asText());
    }
    return name.equals(method);
  }

  private Optional<McpAuthenticationContext> authenticate(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return Optional.empty();
    }
    String token = authorization.substring("Bearer ".length()).trim();
    return token.isEmpty() ? Optional.empty() : bearerTokenVerifier.verify(token);
  }

  private ResponseEntity<JsonNode> unauthorized(HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(
        HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + metadataUrl(request) + "\"");
    return new ResponseEntity<>(null, headers, HttpStatus.UNAUTHORIZED);
  }

  private String metadataUrl(HttpServletRequest request) {
    StringBuilder url =
        new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
    if (("http".equals(request.getScheme()) && request.getServerPort() != 80)
        || ("https".equals(request.getScheme()) && request.getServerPort() != 443)) {
      url.append(':').append(request.getServerPort());
    }
    return url.append("/.well-known/oauth-protected-resource/mcp").toString();
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

  private ResponseEntity<JsonNode> jsonRpcResult(JsonNode id, JsonNode result) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    return ResponseEntity.ok(response);
  }

  private ResponseEntity<JsonNode> jsonRpcError(JsonNode id, int code, String message) {
    ObjectNode error = objectMapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("error", error);
    return ResponseEntity.ok(response);
  }

  private ResponseEntity<JsonNode> badRequest(JsonNode id, int code, String message) {
    ObjectNode response = (ObjectNode) jsonRpcError(id, code, message).getBody().deepCopy();
    return ResponseEntity.badRequest().body(response);
  }

  private boolean isRequest(JsonNode request) {
    return request != null
        && request.isObject()
        && "2.0".equals(request.path("jsonrpc").asText())
        && request.hasNonNull("id")
        && request.hasNonNull("method")
        && request.get("method").isTextual();
  }
}
