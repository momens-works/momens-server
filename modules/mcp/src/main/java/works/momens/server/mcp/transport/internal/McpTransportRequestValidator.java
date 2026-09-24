package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class McpTransportRequestValidator {

  private static final String PROTOCOL_VERSION = "2026-07-28";
  private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
  private static final String METHOD_HEADER = "Mcp-Method";
  private static final String NAME_HEADER = "Mcp-Name";
  private static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

  boolean isJsonRpcRequest(JsonNode request) {
    return request != null
        && request.isObject()
        && "2.0".equals(request.path("jsonrpc").asText())
        && request.hasNonNull("id")
        && (request.get("id").isTextual() || request.get("id").isIntegralNumber())
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
        || !metadata.path("io.modelcontextprotocol/clientCapabilities").isObject()) {
      return false;
    }
    JsonNode clientInfo = metadata.get(CLIENT_INFO);
    if (clientInfo != null
        && (!clientInfo.isObject()
            || !clientInfo.path("name").isTextual()
            || !clientInfo.path("version").isTextual())) {
      return false;
    }
    if (!requiresName(method)) {
      return true;
    }
    String name = request.getHeader(NAME_HEADER);
    if (name == null || name.isBlank()) {
      return false;
    }
    String expectedName =
        switch (method) {
          case "tools/call", "prompts/get" -> body.path("params").path("name").asText();
          case "resources/read" -> body.path("params").path("uri").asText();
          default -> "";
        };
    return decodeName(name).map(expectedName::equals).orElse(false);
  }

  private static boolean requiresName(String method) {
    return switch (method) {
      case "tools/call", "resources/read", "prompts/get" -> true;
      default -> false;
    };
  }

  private static Optional<String> decodeName(String value) {
    if (!value.startsWith("=?base64?") || !value.endsWith("?=")) {
      return Optional.of(value);
    }
    String encoded = value.substring("=?base64?".length(), value.length() - 2);
    try {
      return Optional.of(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }
}
