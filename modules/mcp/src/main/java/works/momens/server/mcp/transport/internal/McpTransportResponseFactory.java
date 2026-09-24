package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class McpTransportResponseFactory {

  private final ObjectMapper objectMapper;

  McpTransportResponseFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  ResponseEntity<JsonNode> unauthorized(HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(
        HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + metadataUrl(request) + "\"");
    return new ResponseEntity<>(null, headers, HttpStatus.UNAUTHORIZED);
  }

  ResponseEntity<JsonNode> jsonRpcResult(JsonNode id, JsonNode result) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    return ResponseEntity.ok(response);
  }

  ResponseEntity<JsonNode> jsonRpcError(JsonNode id, int code, String message) {
    ObjectNode error = objectMapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("error", error);
    return ResponseEntity.ok(response);
  }

  ResponseEntity<JsonNode> badRequest(JsonNode id, int code, String message) {
    ObjectNode response = (ObjectNode) jsonRpcError(id, code, message).getBody().deepCopy();
    return ResponseEntity.badRequest().body(response);
  }

  ResponseEntity<JsonNode> notFound(JsonNode id, int code, String message) {
    ObjectNode response = (ObjectNode) jsonRpcError(id, code, message).getBody().deepCopy();
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
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
}
