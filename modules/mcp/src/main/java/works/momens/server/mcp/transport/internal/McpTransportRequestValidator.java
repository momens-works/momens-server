package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class McpTransportRequestValidator {

  private static final String PROTOCOL_VERSION = "2026-07-28";
  private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
  private static final String METHOD_HEADER = "Mcp-Method";
  private static final String NAME_HEADER = "Mcp-Name";

  boolean isJsonRpcRequest(JsonNode request) {
    return request != null
        && request.isObject()
        && "2.0".equals(request.path("jsonrpc").asText())
        && request.hasNonNull("id")
        && request.hasNonNull("method")
        && request.get("method").isTextual();
  }

  boolean hasLatestProtocolHeaders(HttpServletRequest request, JsonNode body, String method) {
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
}
