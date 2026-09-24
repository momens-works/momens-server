package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;

@Component
public class McpTransportHandler {

  private final McpTransportAuthenticator authenticator;
  private final McpTransportRequestValidator requestValidator;
  private final McpTransportMethodHandler methodHandler;
  private final McpTransportResponseFactory responseFactory;

  McpTransportHandler(
      McpTransportAuthenticator authenticator,
      McpTransportRequestValidator requestValidator,
      McpTransportMethodHandler methodHandler,
      McpTransportResponseFactory responseFactory) {
    this.authenticator = authenticator;
    this.requestValidator = requestValidator;
    this.methodHandler = methodHandler;
    this.responseFactory = responseFactory;
  }

  public ResponseEntity<JsonNode> handle(JsonNode request, HttpServletRequest httpRequest) {
    Optional<McpAuthenticationContext> authentication = authenticator.authenticate(httpRequest);
    if (authentication.isEmpty()) {
      return responseFactory.unauthorized(httpRequest);
    }
    if (!requestValidator.isJsonRpcRequest(request)) {
      return responseFactory.jsonRpcError(null, -32600, "Invalid Request");
    }

    JsonNode id = request.get("id");
    String method = request.get("method").asText();
    if (!requestValidator.hasLatestProtocolHeaders(httpRequest, request, method)) {
      return responseFactory.badRequest(id, -32600, "Invalid Request");
    }
    return methodHandler.handle(method, id, authentication.get());
  }
}
