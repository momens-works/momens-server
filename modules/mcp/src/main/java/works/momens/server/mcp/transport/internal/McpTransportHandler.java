package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.mcp.transport.McpAuthenticationContext;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpTransportHandler {

  private final McpTransportAuthenticator authenticator;
  private final ObjectMapper objectMapper;
  private final McpTransportRequestValidator requestValidator;
  private final McpTransportMethodHandler methodHandler;
  private final McpTransportResponseFactory responseFactory;

  public ResponseEntity<JsonNode> handle(String requestBody, HttpServletRequest httpRequest) {
    Optional<McpAuthenticationContext> authentication = authenticator.authenticate(httpRequest);
    if (authentication.isEmpty()) {
      return responseFactory.unauthorized();
    }
    JsonNode request;
    try {
      request = objectMapper.readTree(requestBody);
    } catch (JacksonException exception) {
      return responseFactory.badRequest(null, -32700, "Parse error");
    }
    if (!requestValidator.isJsonRpcRequest(request)) {
      return responseFactory.badRequest(null, -32600, "Invalid Request");
    }

    JsonNode id = request.get("id");
    String method = request.get("method").asText();
    if (!requestValidator.hasMatchingHeaders(httpRequest, request, method)) {
      return responseFactory.badRequest(id, -32020, "Header mismatch");
    }
    String requestedVersion = requestValidator.protocolVersion(request);
    if (!McpProtocol.VERSION.equals(requestedVersion)) {
      return responseFactory.unsupportedProtocolVersion(id, requestedVersion);
    }
    if (!requestValidator.hasValidClientMetadata(request)) {
      return responseFactory.jsonRpcError(id, -32602, "Invalid params");
    }
    return methodHandler.handle(method, id, authentication.get());
  }
}
