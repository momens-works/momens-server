package works.momens.server.mcp.transport.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class McpTransportResponseFactory {

  private final ObjectMapper objectMapper;
  private final McpEndpointProperties endpointProperties;

  ResponseEntity<JsonNode> unauthorized() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(
        HttpHeaders.WWW_AUTHENTICATE,
        "Bearer resource_metadata=\"" + endpointProperties.protectedResourceMetadataUri() + "\"");
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
    return jsonRpcError(id, code, message, null, HttpStatus.OK);
  }

  ResponseEntity<JsonNode> badRequest(JsonNode id, int code, String message) {
    return jsonRpcError(id, code, message, null, HttpStatus.BAD_REQUEST);
  }

  ResponseEntity<JsonNode> badRequest(JsonNode id, int code, String message, JsonNode data) {
    return jsonRpcError(id, code, message, data, HttpStatus.BAD_REQUEST);
  }

  ResponseEntity<JsonNode> unsupportedProtocolVersion(JsonNode id, String requestedVersion) {
    ObjectNode data = objectMapper.createObjectNode();
    data.putArray("supported").add(McpProtocol.VERSION);
    data.put("requested", requestedVersion);
    return badRequest(id, -32022, "Unsupported protocol version", data);
  }

  ResponseEntity<JsonNode> notFound(JsonNode id, int code, String message) {
    return jsonRpcError(id, code, message, null, HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<JsonNode> jsonRpcError(
      JsonNode id, int code, String message, JsonNode data, HttpStatus status) {
    ObjectNode error = objectMapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    if (data != null) {
      error.set("data", data);
    }
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("error", error);
    return ResponseEntity.status(status).body(response);
  }
}
