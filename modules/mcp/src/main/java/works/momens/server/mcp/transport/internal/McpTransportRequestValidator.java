package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class McpTransportRequestValidator {

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

  boolean hasMatchingHeaders(HttpServletRequest request, JsonNode body, String method) {
    if (!protocolVersion(body).equals(request.getHeader(McpProtocol.VERSION_HEADER))
        || !method.equals(request.getHeader(McpProtocol.METHOD_HEADER))
        || !hasRequiredAcceptTypes(request)) {
      return false;
    }
    if (!requiresName(method)) {
      return true;
    }
    String name = request.getHeader(McpProtocol.NAME_HEADER);
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

  private static boolean hasRequiredAcceptTypes(HttpServletRequest request) {
    try {
      List<MediaType> accepted = MediaType.parseMediaTypes(request.getHeader("Accept"));
      return accepted.stream()
              .map(MediaType::removeQualityValue)
              .anyMatch(MediaType.APPLICATION_JSON::equals)
          && accepted.stream()
              .map(MediaType::removeQualityValue)
              .anyMatch(MediaType.TEXT_EVENT_STREAM::equals);
    } catch (InvalidMediaTypeException | NullPointerException exception) {
      return false;
    }
  }

  String protocolVersion(JsonNode body) {
    return body.path("params")
        .path("_meta")
        .path("io.modelcontextprotocol/protocolVersion")
        .asText();
  }

  boolean hasValidClientMetadata(JsonNode body) {
    JsonNode metadata = body.path("params").path("_meta");
    if (!metadata.path("io.modelcontextprotocol/clientCapabilities").isObject()) {
      return false;
    }
    JsonNode clientInfo = metadata.get(CLIENT_INFO);
    if (clientInfo != null
        && (!clientInfo.isObject()
            || !clientInfo.path("name").isTextual()
            || !clientInfo.path("version").isTextual())) {
      return false;
    }
    return true;
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
